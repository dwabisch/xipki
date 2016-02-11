/*
 *
 * This file is part of the XiPKI project.
 * Copyright (c) 2013 - 2016 Lijun Liao
 * Author: Lijun Liao
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License version 3
 * as published by the Free Software Foundation with the addition of the
 * following permission added to Section 15 as permitted in Section 7(a):
 * FOR ANY PART OF THE COVERED WORK IN WHICH THE COPYRIGHT IS OWNED BY
 * THE AUTHOR LIJUN LIAO. LIJUN LIAO DISCLAIMS THE WARRANTY OF NON INFRINGEMENT
 * OF THIRD PARTY RIGHTS.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 * The interactive user interfaces in modified source and object code versions
 * of this program must display Appropriate Legal Notices, as required under
 * Section 5 of the GNU Affero General Public License.
 *
 * You can be released from the requirements of the license by purchasing
 * a commercial license. Buying such a license is mandatory as soon as you
 * develop commercial activities involving the XiPKI software without
 * disclosing the source code of your own applications.
 *
 * For more information, please contact Lijun Liao at this
 * address: lijun.liao@gmail.com
 */

package org.xipki.commons.security.api.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.cmp.PKIFreeText;
import org.bouncycastle.asn1.cmp.PKIStatus;
import org.bouncycastle.asn1.nist.NISTNamedCurves;
import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.asn1.teletrust.TeleTrusTNamedCurves;
import org.bouncycastle.asn1.x9.X962NamedCurves;
import org.xipki.commons.common.ConfPairs;
import org.xipki.commons.common.util.StringUtil;
import org.xipki.commons.security.api.InvalidOidOrNameException;
import org.xipki.commons.security.api.ObjectIdentifiers;

/**
 * @author Lijun Liao
 * @since 2.0.0
 */

public class SecurityUtil {

    private static final Map<Integer, String> STATUS_TEXT_MAP = new HashMap<>();

    private static final String[] FAILUREINFO_TEXTS = new String[] {
        // 0 - 3
        "incorrectData", "wrongAuthority", "badDataFormat", "badCertId",
        // 4 - 7
        "badTime", "badRequest", "badMessageCheck", "badAlg",
        // 8 - 11
        "unacceptedPolicy", "timeNotAvailable", "badRecipientNonce", "wrongIntegrity",
        // 12 - 15
        "certConfirmed", "certRevoked", "badPOP", "missingTimeStamp",
        // 16 - 19
        "notAuthorized", "unsupportedVersion", "transactionIdInUse", "signerNotTrusted",
        // 20 - 23
        "badCertTemplate", "badSenderNonce", "addInfoNotAvailable", "unacceptedExtension",
        // 24 -27
        "-", "-", "-", "-",
        // 28 - 31
        "-", "duplicateCertReq", "systemFailure", "systemUnavail"};

    static {
        STATUS_TEXT_MAP.put(-2, "xipki_noAnswer");
        STATUS_TEXT_MAP.put(-1, "xipki_responseError");
        STATUS_TEXT_MAP.put(PKIStatus.GRANTED, "accepted");
        STATUS_TEXT_MAP.put(PKIStatus.GRANTED_WITH_MODS, "grantedWithMods");
        STATUS_TEXT_MAP.put(PKIStatus.REJECTION, "rejection");
        STATUS_TEXT_MAP.put(PKIStatus.WAITING, "waiting");
        STATUS_TEXT_MAP.put(PKIStatus.REVOCATION_WARNING, "revocationWarning");
        STATUS_TEXT_MAP.put(PKIStatus.REVOCATION_NOTIFICATION, "revocationNotification");
        STATUS_TEXT_MAP.put(PKIStatus.KEY_UPDATE_WARNING, "keyUpdateWarning");
    }

    private SecurityUtil() {
    }

    public static byte[] extractMinimalKeyStore(
            final String keystoreType,
            final byte[] keystoreBytes,
            final String keyname,
            final char[] password)
    throws Exception {
        return extractMinimalKeyStore(keystoreType, keystoreBytes, keyname, password, null);
    }

    public static byte[] extractMinimalKeyStore(
            final String keystoreType,
            final byte[] keystoreBytes,
            final String keyname,
            final char[] password,
            final X509Certificate[] newCertChain)
    throws Exception {
        KeyStore ks;
        if ("JKS".equalsIgnoreCase(keystoreType)) {
            ks = KeyStore.getInstance(keystoreType);
        } else {
            ks = KeyStore.getInstance(keystoreType, "BC");
        }
        ks.load(new ByteArrayInputStream(keystoreBytes), password);

        String localKeyname = keyname;
        if (localKeyname == null) {
            Enumeration<String> aliases = ks.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (ks.isKeyEntry(alias)) {
                    localKeyname = alias;
                    break;
                }
            }
        } else {
            if (!ks.isKeyEntry(localKeyname)) {
                throw new KeyStoreException("unknown key named " + localKeyname);
            }
        }

        Enumeration<String> aliases = ks.aliases();
        int numAliases = 0;
        while (aliases.hasMoreElements()) {
            aliases.nextElement();
            numAliases++;
        }

        Certificate[] certs;
        if (newCertChain == null || newCertChain.length < 1) {
            if (numAliases == 1) {
                return keystoreBytes;
            }
            certs = ks.getCertificateChain(localKeyname);
        } else {
            certs = newCertChain;
        }

        PrivateKey key = (PrivateKey) ks.getKey(localKeyname, password);
        ks = null;

        if ("JKS".equalsIgnoreCase(keystoreType)) {
            ks = KeyStore.getInstance(keystoreType);
        } else {
            ks = KeyStore.getInstance(keystoreType, "BC");
        }
        ks.load(null, password);
        ks.setKeyEntry(localKeyname, key, password, certs);
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        ks.store(bout, password);
        byte[] bytes = bout.toByteArray();
        bout.close();
        return bytes;
    } // method extractMinimalKeyStore

    public static String formatPkiStatusInfo(
            final org.bouncycastle.asn1.cmp.PKIStatusInfo pkiStatusInfo) {
        int status = pkiStatusInfo.getStatus().intValue();
        int failureInfo = pkiStatusInfo.getFailInfo().intValue();
        PKIFreeText text = pkiStatusInfo.getStatusString();
        String statusMessage = (text == null)
                ? null
                : text.getStringAt(0).getString();

        return SecurityUtil.formatPkiStatusInfo(status, failureInfo, statusMessage);
    }

    public static String formatPkiStatusInfo(
            final int status,
            final int failureInfo,
            final String statusMessage) {
        StringBuilder sb = new StringBuilder("PKIStatusInfo {");
        sb.append("status = ");
        sb.append(status);
        sb.append(" (").append(STATUS_TEXT_MAP.get(status)).append("), ");
        sb.append("failureInfo = ");
        sb.append(failureInfo).append(" (").append(getFailureInfoText(failureInfo)).append("), ");
        sb.append("statusMessage = ").append(statusMessage);
        sb.append("}");
        return sb.toString();
    }

    public static String getFailureInfoText(
            final int failureInfo) {
        BigInteger b = BigInteger.valueOf(failureInfo);
        final int n = Math.min(b.bitLength(), FAILUREINFO_TEXTS.length);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (b.testBit(i)) {
                sb.append(", ").append(FAILUREINFO_TEXTS[i]);
            }
        }

        return (sb.length() < 3)
                ? ""
                : sb.substring(2);
    }

    public static byte[] leftmost(
            final byte[] bytes,
            final int bitCount) {
        int byteLenKey = (bitCount + 7) / 8;

        if (bitCount >= (bytes.length << 3)) {
            return bytes;
        }

        byte[] truncatedBytes = new byte[byteLenKey];
        System.arraycopy(bytes, 0, truncatedBytes, 0, byteLenKey);

        // shift the bits to the right
        if (bitCount % 8 > 0)    {
            int shiftBits = 8 - (bitCount % 8);

            for (int i = byteLenKey - 1; i > 0; i--) {
                truncatedBytes[i] = (byte)
                        ((byte2int(truncatedBytes[i]) >>> shiftBits)
                        | ((byte2int(truncatedBytes[i - 1]) << (8 - shiftBits)) & 0xFF));
            }
            truncatedBytes[0] = (byte) (byte2int(truncatedBytes[0]) >>> shiftBits);
        }

        return truncatedBytes;
    }

    private static int byte2int(
            final byte b) {
        return (b >= 0)
                ? b
                : 256 + b;
    }

    public static String getCurveName(
            final ASN1ObjectIdentifier curveId) {
        String curveName = X962NamedCurves.getName(curveId);

        if (curveName == null) {
            curveName = SECNamedCurves.getName(curveId);
        }

        if (curveName == null) {
            curveName = TeleTrusTNamedCurves.getName(curveId);
        }

        if (curveName == null) {
            curveName = NISTNamedCurves.getName(curveId);
        }

        return curveName;
    }

    public static List<ASN1ObjectIdentifier> textToASN1ObjectIdentifers(
            final List<String> oidTexts)
    throws InvalidOidOrNameException {
        if (oidTexts == null) {
            return null;
        }

        List<ASN1ObjectIdentifier> ret = new ArrayList<>(oidTexts.size());
        for (String oidText : oidTexts) {
            if (oidText.isEmpty()) {
                continue;
            }

            ASN1ObjectIdentifier oid = toOid(oidText);
            if (!ret.contains(oid)) {
                ret.add(oid);
            }
        }
        return ret;
    }

    private static ASN1ObjectIdentifier toOid(
            final String s)
    throws InvalidOidOrNameException {
        final int n = s.length();
        boolean isName = false;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '1') || c == '.')) {
                isName = true;
            }
        }

        if (!isName) {
            try {
                return new ASN1ObjectIdentifier(s);
            } catch (IllegalArgumentException e) {
            }
        }

        ASN1ObjectIdentifier oid = ObjectIdentifiers.nameToOid(s);
        if (oid == null) {
            throw new InvalidOidOrNameException(s);
        }
        return oid;
    }

    public static String signerConfToString(
            final String signerConf,
            final boolean verbose,
            final boolean ignoreSensitiveInfo) {
        String localSignerConf = signerConf;
        if (ignoreSensitiveInfo) {
            localSignerConf = SecurityUtil.eraseSensitiveData(localSignerConf);
        }

        if (verbose || localSignerConf.length() < 101) {
            return localSignerConf;
        } else {
            return new StringBuilder().append(localSignerConf.substring(0, 97))
                    .append("...").toString();
        }
    }

    private static String eraseSensitiveData(
            final String conf) {
        if (conf == null || !conf.contains("password?")) {
            return conf;
        }

        try {
            ConfPairs pairs = new ConfPairs(conf);
            String value = pairs.getValue("password");
            if (value != null && !StringUtil.startsWithIgnoreCase(value, "PBE:")) {
                pairs.putPair("password", "<sensitve>");
            }
            return pairs.getEncoded();
        } catch (Exception e) {
            return conf;
        }
    }

}
