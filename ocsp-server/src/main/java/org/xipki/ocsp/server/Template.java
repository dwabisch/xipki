/*
 *
 * Copyright (c) 2013 - 2020 Lijun Liao
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.xipki.ocsp.server;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.isismtt.ISISMTTObjectIdentifiers;
import org.bouncycastle.asn1.isismtt.ocsp.CertHash;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.util.Arrays;
import org.xipki.ocsp.server.type.ASN1Type;
import org.xipki.ocsp.server.type.ExtendedExtension;
import org.xipki.ocsp.server.type.Extension;
import org.xipki.ocsp.server.type.OID;
import org.xipki.ocsp.server.type.WritableOnlyExtension;
import org.xipki.security.CrlReason;
import org.xipki.security.HashAlgo;

/**
 * OCSP response template.
 *
 * @author Lijun Liao
 * @since 2.2.0
 */

class Template {

  private static final Map<HashAlgo, byte[]> extnCerthashPrefixMap = new HashMap<>();

  private static final byte[] extnInvalidityDate;

  private static final byte[] extnArchiveCutof;

  private static final byte[] revokedInfoNoReasonPrefix = new byte[]{(byte) 0xA1, 0x11};

  private static final byte[] revokedInfoWithReasonPrefix = new byte[]{(byte) 0xA1, 0x16};

  private static final byte[] reasonPrefix = new byte[]{(byte) 0xa0, 0x03, 0x0a, 0x01};

  static {
    // CertHash
    for (HashAlgo h : HashAlgo.values()) {
      int hlen = h.getLength();

      AlgorithmIdentifier algId = new AlgorithmIdentifier(h.getOid(), DERNull.INSTANCE);
      byte[] encoded;
      try {
        CertHash certHash = new CertHash(algId, new byte[hlen]);
        org.bouncycastle.asn1.x509.Extension extn = new org.bouncycastle.asn1.x509.Extension(
              ISISMTTObjectIdentifiers.id_isismtt_at_certHash, false, certHash.getEncoded());
        encoded = extn.getEncoded();
      } catch (IOException ex) {
        throw new ExceptionInInitializerError("could not processing encoding of CertHash");
      }
      byte[] prefix = Arrays.copyOf(encoded, encoded.length - hlen);
      extnCerthashPrefixMap.put(h, prefix);
    }

    Extension extension = new ExtendedExtension(OID.ID_INVALIDITY_DATE, false, new byte[17]);
    extnInvalidityDate = new byte[extension.getEncodedLength()];
    extension.write(extnInvalidityDate, 0);

    extension = new ExtendedExtension(OID.ID_PKIX_OCSP_ARCHIVE_CUTOFF, false, new byte[17]);
    extnArchiveCutof = new byte[extension.getEncodedLength()];
    extension.write(extnArchiveCutof, 0);
  } // method static

  public static WritableOnlyExtension getCertHashExtension(HashAlgo hashAlgo, byte[] certHash) {
    if (hashAlgo.getLength() != certHash.length) {
      throw new IllegalArgumentException("hashAlgo and certHash do not match");
    }

    byte[] encodedPrefix = extnCerthashPrefixMap.get(hashAlgo);
    byte[] rv = new byte[encodedPrefix.length + certHash.length];
    System.arraycopy(encodedPrefix, 0, rv, 0, encodedPrefix.length);
    System.arraycopy(certHash, 0, rv, encodedPrefix.length, certHash.length);

    return new WritableOnlyExtension(rv);
  } // method getCertHashExtension

  public static WritableOnlyExtension getInvalidityDateExtension(Date invalidityDate) {
    int len = extnInvalidityDate.length;
    byte[] encoded = new byte[len];
    System.arraycopy(extnInvalidityDate, 0, encoded, 0, len - 17);
    ASN1Type.writeGeneralizedTime(invalidityDate, encoded, len - 17);
    return new WritableOnlyExtension(encoded);
  } // method getInvalidityDateExtension

  public static WritableOnlyExtension getArchiveOffExtension(Date archiveCutoff) {
    int len = extnArchiveCutof.length;
    byte[] encoded = new byte[len];
    System.arraycopy(extnArchiveCutof, 0, encoded, 0, len - 17);
    ASN1Type.writeGeneralizedTime(archiveCutoff, encoded, len - 17);
    return new WritableOnlyExtension(encoded);
  } // method getArchiveOffExtension

  public static byte[] getEncodeRevokedInfo(CrlReason reason, Date revocationTime) {
    if (reason == null) {
      byte[] encoded = new byte[19];
      System.arraycopy(revokedInfoNoReasonPrefix, 0, encoded, 0, 2);
      ASN1Type.writeGeneralizedTime(revocationTime, encoded, 2);
      return encoded;
    } else {
      byte[] encoded = new byte[24];
      System.arraycopy(revokedInfoWithReasonPrefix, 0, encoded, 0, 2);
      ASN1Type.writeGeneralizedTime(revocationTime, encoded, 2);
      System.arraycopy(reasonPrefix, 0, encoded, 19, 4);
      encoded[23] = (byte) reason.getCode();
      return encoded;
    }
  } // method getEncodeRevokedInfo

}
