/*
 *
 * Copyright (c) 2013 - 2019 Lijun Liao
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

package org.xipki.ocsp.server.store.crl;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Arrays;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1GeneralizedTime;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.ASN1Set;
import org.bouncycastle.asn1.ASN1TaggedObject;
import org.bouncycastle.asn1.DERIA5String;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.DERUTF8String;
import org.bouncycastle.asn1.ocsp.CrlID;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Certificate;
import org.bouncycastle.asn1.x509.TBSCertificate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.datasource.DataAccessException;
import org.xipki.datasource.DataSourceWrapper;
import org.xipki.ocsp.server.store.DbCertStatusStore;
import org.xipki.security.CertRevocationInfo;
import org.xipki.security.CrlReason;
import org.xipki.security.CrlStreamParser;
import org.xipki.security.CrlStreamParser.RevokedCert;
import org.xipki.security.CrlStreamParser.RevokedCertsIterator;
import org.xipki.security.HashAlgo;
import org.xipki.security.ObjectIdentifiers;
import org.xipki.security.util.X509Util;
import org.xipki.util.Args;
import org.xipki.util.Base64;
import org.xipki.util.DateUtil;
import org.xipki.util.IoUtil;
import org.xipki.util.LogUtil;
import org.xipki.util.StringUtil;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.2.0
 */

class ImportCrl {

  static class ImportCrlException extends Exception {

    private static final long serialVersionUID = 1L;

    public ImportCrlException() {
      super();
    }

    public ImportCrlException(String message, Throwable cause) {
      super(message, cause);
    }

    public ImportCrlException(String message) {
      super(message);
    }

    public ImportCrlException(Throwable cause) {
      super(cause);
    }

  }

  private static final Logger LOG = LoggerFactory.getLogger(ImportCrl.class);

  private static final String KEY_CA_REVOCATION_TIME = "ca.revocation.time";

  private static final String KEY_CA_INVALIDITY_TIME = "ca.invalidity.time";

  private static final String SQL_UPDATE_CERT_REV
      = "UPDATE CERT SET REV=?,RR=?,RT=?,RIT=?,LUPDATE=? WHERE ID=?";

  private static final String SQL_INSERT_CERT_REV
      = "INSERT INTO CERT (ID,IID,SN,REV,RR,RT,RIT,LUPDATE) VALUES(?,?,?,?,?,?,?,?)";

  private static final String SQL_DELETE_CERT = "DELETE FROM CERT WHERE IID=? AND SN=?";

  private static final String SQL_UPDATE_CERT
      = "UPDATE CERT SET LUPDATE=?,NBEFORE=?,NAFTER=?,HASH=? WHERE ID=?";

  private static final String SQL_INSERT_CERT
      = "INSERT INTO CERT (ID,IID,SN,REV,RR,RT,RIT,LUPDATE,NBEFORE,NAFTER,HASH) "
        + "VALUES(?,?,?,?,?,?,?,?,?,?,?)";

  private static final String CORE_SQL_SELECT_ID_CERT = "ID FROM CERT WHERE IID=? AND SN=?";

  private final String basedir;

  private final String sqlSelectIdCert;

  private final Certificate caCert;

  private final DataSourceWrapper datasource;

  private final CrlStreamParser crl;

  private final CrlID crlId;

  private final X500Name caSubject;

  private final byte[] caSpki;

  private final CertRevocationInfo caRevInfo;

  private final HashAlgo certhashAlgo;

  private PreparedStatement psDeleteCert;

  private PreparedStatement psInsertCert;

  private PreparedStatement psInsertCertRev;

  private PreparedStatement psSelectIdCert;

  private PreparedStatement psUpdateCert;

  private PreparedStatement psUpdateCertRev;

  public ImportCrl(DataSourceWrapper datasource, String basedir)
      throws ImportCrlException, DataAccessException, IOException {
    this.datasource = Args.notNull(datasource, "datasource");
    this.basedir = Args.notNull(basedir, "basedir");
    this.certhashAlgo = DbCertStatusStore.getCertHashAlgo(datasource);

    File caCertFile = new File(basedir, "ca.crt");
    if (!caCertFile.exists()) {
      throw new ImportCrlException(
          "CA certificate file " + caCertFile.getPath() + " does not exist");
    }

    File crlFile = new File(basedir, "ca.crl");
    if (!crlFile.exists()) {
      throw new ImportCrlException("CRL file " + caCertFile.getPath() + " does not exist");
    }

    LOG.info("UPDATE_CERTSTORE: a newer CRL is available");

    this.caCert = parseCert(caCertFile);
    this.caSubject = caCert.getSubject();
    try {
      this.caSpki = X509Util.extractSki(caCert);
    } catch (CertificateEncodingException ex) {
      throw new ImportCrlException("could not extract AKI of CA certificate", ex);
    }

    Certificate issuerCert = null;
    File issuerCertFile = new File(basedir, "issuer.crt");
    if (issuerCertFile.exists()) {
      issuerCert = parseCert(issuerCertFile);
    }

    File revFile = new File(basedir, "REVOCATION");
    CertRevocationInfo caRevInfo = null;
    if (revFile.exists()) {
      Properties props = new Properties();
      InputStream is = Files.newInputStream(revFile.toPath());
      try {
        props.load(is);
      } finally {
        is.close();
      }

      String str = props.getProperty(KEY_CA_REVOCATION_TIME);
      if (StringUtil.isNotBlank(str)) {
        Date revocationTime = DateUtil.parseUtcTimeyyyyMMddhhmmss(str);
        Date invalidityTime = null;

        str = props.getProperty(KEY_CA_INVALIDITY_TIME);
        if (StringUtil.isNotBlank(str)) {
          invalidityTime = DateUtil.parseUtcTimeyyyyMMddhhmmss(str);
        }
        caRevInfo = new CertRevocationInfo(CrlReason.UNSPECIFIED, revocationTime, invalidityTime);
      }
    }

    this.caRevInfo = caRevInfo;

    this.crl = new CrlStreamParser(crlFile);
    X500Name issuer = crl.getIssuer();

    Certificate crlSignerCert;
    if (caSubject.equals(issuer)) {
      crlSignerCert = caCert;
    } else {
      if (issuerCert == null) {
        throw new IllegalArgumentException("issuerCert may not be null");
      }

      if (!issuerCert.getSubject().equals(issuer)) {
        throw new IllegalArgumentException("issuerCert and CRL do not match");
      }
      crlSignerCert = issuerCert;
    }

    // Verify the signature
    if (!crl.verifySignature(crlSignerCert.getSubjectPublicKeyInfo())) {
      throw new ImportCrlException("signature of CRL is invalid");
    }

    if (crl.getCrlNumber() == null) {
      throw new ImportCrlException("crlNumber is not specified");
    }

    LOG.info("The CRL is a {}", crl.isDeltaCrl() ? "DeltaCRL" : "FullCRL");

    // Construct CrlID
    ASN1EncodableVector vec = new ASN1EncodableVector();
    File urlFile = new File(basedir, "crl.url");
    if (urlFile.exists()) {
      String crlUrl = StringUtil.toUtf8String(IoUtil.read(urlFile)).trim();
      if (StringUtil.isNotBlank(crlUrl)) {
        vec.add(new DERTaggedObject(true, 0, new DERIA5String(crlUrl, true)));
      }
    }

    vec.add(new DERTaggedObject(true, 1, new ASN1Integer(crl.getCrlNumber())));
    vec.add(new DERTaggedObject(true, 2,
                new ASN1GeneralizedTime(crl.getThisUpdate())));
    this.crlId = CrlID.getInstance(new DERSequence(vec));

    this.sqlSelectIdCert = datasource.buildSelectFirstSql(1, CORE_SQL_SELECT_ID_CERT);
  }

  public boolean importCrlToOcspDb() {
    Connection conn = null;
    try {
      conn = datasource.getConnection();

      // CHECKSTYLE:SKIP
      Date startTime = new Date();
      // CHECKSTYLE:SKIP
      int caId = importCa(conn);

      psDeleteCert = datasource.prepareStatement(conn, SQL_DELETE_CERT);
      psInsertCert = datasource.prepareStatement(conn, SQL_INSERT_CERT);
      psInsertCertRev = datasource.prepareStatement(conn, SQL_INSERT_CERT_REV);
      psSelectIdCert = datasource.prepareStatement(conn, sqlSelectIdCert);
      psUpdateCert = datasource.prepareStatement(conn, SQL_UPDATE_CERT);
      psUpdateCertRev = datasource.prepareStatement(conn, SQL_UPDATE_CERT_REV);

      importEntries(conn, caId);
      if (!crl.isDeltaCrl()) {
        deleteEntriesNotUpdatedSince(conn, startTime);
      }

      return true;
    } catch (Throwable th) {
      LogUtil.error(LOG, th, "could not import CRL to OCSP database");
      releaseResources(psDeleteCert, null);
      releaseResources(psInsertCert, null);
      releaseResources(psInsertCertRev, null);
      releaseResources(psSelectIdCert, null);
      releaseResources(psUpdateCert, null);
      releaseResources(psUpdateCertRev, null);

      if (conn != null) {
        datasource.returnConnection(conn);
      }
    }

    return false;
  }

  private int importCa(Connection conn)
      throws DataAccessException, ImportCrlException {
    byte[] encodedCaCert;
    try {
      encodedCaCert = caCert.getEncoded();
    } catch (IOException ex) {
      throw new ImportCrlException("could not encode CA certificate");
    }
    String fpCaCert = HashAlgo.SHA1.base64Hash(encodedCaCert);

    Integer issuerId = null;
    CrlInfo crlInfo = null;

    PreparedStatement ps = null;
    ResultSet rs = null;
    String sql = null;
    try {
      sql = "SELECT ID,CRL_INFO FROM ISSUER WHERE S1C=?";
      ps = datasource.prepareStatement(conn, sql);
      ps.setString(1, fpCaCert);
      rs = ps.executeQuery();
      if (rs.next()) {
        issuerId = rs.getInt("ID");
        String str = rs.getString("CRL_INFO");
        if (str == null) {
          throw new ImportCrlException(
            "RequestIssuer for the given CA of CRL exists, but not imported from CRL");
        }
        crlInfo = new CrlInfo(str);
      }
    } catch (SQLException ex) {
      throw datasource.translate(sql, ex);
    } finally {
      releaseResources(ps, rs);
    }

    boolean addNew = (issuerId == null);
    BigInteger crlNumber = crl.getCrlNumber();
    BigInteger baseCrlNumber = crl.getBaseCrlNumber();
    if (addNew) {
      if (crl.isDeltaCrl()) {
        throw new ImportCrlException("Given CRL is a DeltaCRL for the full CRL with number "
            + baseCrlNumber + ", please import this full CRL first.");
      } else {
        crlInfo = new CrlInfo(crlNumber, null, crl.getThisUpdate(), crl.getNextUpdate(), crlId);
      }
    } else {
      if (crlNumber.compareTo(crlInfo.getCrlNumber()) <= 0) {
        // It is permitted if the CRL number equals to the one in Database,
        // which enables the resume of importing process if error occurred.
        throw new ImportCrlException("Given CRL is not newer than existing CRL.");
      }

      if (crl.isDeltaCrl()) {
        BigInteger lastFullCrlNumber = crlInfo.getBaseCrlNumber();
        if (lastFullCrlNumber == null) {
          lastFullCrlNumber = crlInfo.getCrlNumber();
        }

        if (!baseCrlNumber.equals(lastFullCrlNumber)) {
          throw new ImportCrlException("Given CRL is a deltaCRL for the full CRL with number "
              + crlNumber + ", please import this full CRL first.");
        }
      }

      crlInfo.setCrlNumber(crlNumber);
      crlInfo.setBaseCrlNumber(crl.isDeltaCrl() ? baseCrlNumber : null);
      crlInfo.setThisUpdate(crl.getThisUpdate());
      crlInfo.setNextUpdate(crl.getNextUpdate());
    }

    ps = null;
    rs = null;
    sql = null;
    try {
      // issuer exists
      int offset = 1;
      if (addNew) {
        int maxId = (int) datasource.getMax(conn, "ISSUER", "ID");
        issuerId = maxId + 1;

        sql = "INSERT INTO ISSUER (ID,SUBJECT,NBEFORE,NAFTER,S1C,CERT,REV_INFO,CRL_INFO)"
            + " VALUES(?,?,?,?,?,?,?,?)";
        ps = datasource.prepareStatement(conn, sql);
        String subject = X509Util.getRfc4519Name(caCert.getSubject());

        ps.setInt(offset++, issuerId);
        ps.setString(offset++, subject);
        ps.setLong(offset++, caCert.getStartDate().getDate().getTime() / 1000);
        ps.setLong(offset++, caCert.getEndDate().getDate().getTime() / 1000);
        ps.setString(offset++, fpCaCert);
        ps.setString(offset++, Base64.encodeToString(encodedCaCert));
      } else {
        sql = "UPDATE ISSUER SET REV_INFO=?,CRL_INFO=? WHERE ID=?";
        ps = datasource.prepareStatement(conn, sql);
      }

      ps.setString(offset++, (caRevInfo == null) ? null : caRevInfo.getEncoded());

      // CRL info
      try {
        ps.setString(offset++, crlInfo.getEncoded());
      } catch (IOException ex) {
        throw new ImportCrlException("could not encode the Crlinfo", ex);
      }

      if (!addNew) {
        ps.setInt(offset++, issuerId.intValue());
      }

      ps.executeUpdate();
      return issuerId.intValue();
    } catch (SQLException ex) {
      throw datasource.translate(sql, ex);
    } finally {
      releaseResources(ps, rs);
    }
  }

  private void importEntries(Connection conn, int caId)
      throws DataAccessException, ImportCrlException, IOException {
    AtomicLong maxId = new AtomicLong(datasource.getMax(conn, "CERT", "ID"));

    boolean isDeltaCrl = crl.isDeltaCrl();
    // import the revoked information
    try (RevokedCertsIterator revokedCertList = crl.revokedCertificates()) {
      while (revokedCertList.hasNext()) {
        RevokedCert revCert = revokedCertList.next();
        BigInteger serial = revCert.getSerialNumber();
        Date rt = revCert.getRevocationDate();
        Date rit = revCert.getInvalidityDate();
        CrlReason reason = revCert.getReason();
        X500Name issuer = revCert.getCertificateIssuer();
        if (issuer != null && !issuer.equals(caSubject)) {
          throw new ImportCrlException("invalid CRLEntry for certificate number " + serial);
        }

        String sql = null;
        try {
          if (reason == CrlReason.REMOVE_FROM_CRL) {
            if (isDeltaCrl) {
              // delete the entry
              sql = SQL_DELETE_CERT;
              psDeleteCert.setInt(1, caId);
              psDeleteCert.setString(2, serial.toString(16));
              psDeleteCert.executeUpdate();
            } else {
              LOG.warn("ignore CRL entry with reason removeFromCRL in non-Delta CRL");
            }
            continue;
          }

          Long id = getId(caId, serial);
          PreparedStatement ps;
          int offset = 1;

          if (id == null) {
            sql = SQL_INSERT_CERT_REV;
            id = maxId.incrementAndGet();
            ps = psInsertCertRev;
            ps.setLong(offset++, id);
            ps.setInt(offset++, caId);
            ps.setString(offset++, serial.toString(16));
          } else {
            sql = SQL_UPDATE_CERT_REV;
            ps = psUpdateCertRev;
          }

          ps.setInt(offset++, 1);
          ps.setInt(offset++, reason.getCode());
          ps.setLong(offset++, rt.getTime() / 1000);
          if (rit != null) {
            ps.setLong(offset++, rit.getTime() / 1000);
          } else {
            ps.setNull(offset++, Types.BIGINT);
          }
          ps.setLong(offset++, System.currentTimeMillis() / 1000);

          if (ps == psUpdateCertRev) {
            ps.setLong(offset++, id);
          }

          ps.executeUpdate();
        } catch (SQLException ex) {
          throw datasource.translate(sql, ex);
        }
      }
    }

    // import the certificates

    // extract the certificate
    byte[] extnValue = X509Util.getCoreExtValue(
                          crl.getCrlExtensions(), ObjectIdentifiers.Xipki.id_xipki_ext_crlCertset);
    if (extnValue != null) {
      ASN1Set asn1Set = DERSet.getInstance(extnValue);
      final int n = asn1Set.size();

      for (int i = 0; i < n; i++) {
        ASN1Encodable asn1 = asn1Set.getObjectAt(i);
        ASN1Sequence seq = ASN1Sequence.getInstance(asn1);
        BigInteger serialNumber = ASN1Integer.getInstance(seq.getObjectAt(0)).getValue();

        Certificate cert = null;
        String profileName = null;

        final int size = seq.size();
        for (int j = 1; j < size; j++) {
          ASN1TaggedObject taggedObj = DERTaggedObject.getInstance(seq.getObjectAt(j));
          int tagNo = taggedObj.getTagNo();
          switch (tagNo) {
            case 0:
              cert = Certificate.getInstance(taggedObj.getObject());
              break;
            case 1:
              profileName = DERUTF8String.getInstance(taggedObj.getObject()).getString();
              break;
            default:
              break;
          }
        }

        if (cert == null) {
          continue;
        }

        if (!caSubject.equals(cert.getIssuer())) {
          LOG.warn("issuer not match (serial={}) in CRL Extension Xipki-CertSet, ignore it",
              LogUtil.formatCsn(serialNumber));
          continue;
        }

        if (!serialNumber.equals(cert.getSerialNumber().getValue())) {
          LOG.warn("serialNumber not match (serial={}) in CRL Extension Xipki-CertSet, ignore it",
              LogUtil.formatCsn(serialNumber));
          continue;
        }

        String certLogId = "(issuer='" + cert.getIssuer()
            + "', serialNumber=" + cert.getSerialNumber() + ")";
        addCertificate(maxId, caId, cert, profileName, certLogId);
      }
    } else {
      // cert dirs
      File certsDir = new File(basedir, "certs");

      if (!certsDir.exists()) {
        LOG.warn("the folder {} does not exist, ignore it", certsDir.getPath());
        return;
      }

      if (!certsDir.isDirectory()) {
        LOG.warn("the path {} does not point to a folder, ignore it", certsDir.getPath());
        return;
      }

      if (!certsDir.canRead()) {
        LOG.warn("the folder {} may not be read, ignore it", certsDir.getPath());
        return;
      }

      // import certificates
      File[] certFiles = certsDir.listFiles(new FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
          return name.endsWith(".der") || name.endsWith(".crt") || name.endsWith(".pem");
        }
      });

      if (certFiles != null && certFiles.length > 0) {
        for (File certFile : certFiles) {
          Certificate cert;
          try {
            cert = X509Util.parseBcCert(certFile);
          } catch (IllegalArgumentException | IOException | CertificateException ex) {
            LOG.warn("could not parse certificate {}, ignore it", certFile.getPath());
            continue;
          }

          String certLogId = "(file " + certFile.getName() + ")";
          addCertificate(maxId, caId, cert, null, certLogId);
        }
      }

      // import certificate serial numbers
      File[] serialNumbersFiles = certsDir.listFiles(new FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
          return name.endsWith(".serials");
        }
      });

      if (serialNumbersFiles != null && serialNumbersFiles.length > 0) {
        for (File serialNumbersFile : serialNumbersFiles) {
          try (BufferedReader reader = new BufferedReader(new FileReader(serialNumbersFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
              BigInteger serialNumber = new BigInteger(line.trim(), 16);
              addCertificateBySerialNumber(maxId, caId, serialNumber);
            }
          } catch (IOException ex) {
            LOG.warn("could not import certificates by serial numbers from file {}, ignore it",
                serialNumbersFile.getPath());
            continue;
          }
        }
      }
    }
  }

  private static Certificate parseCert(File certFile) throws ImportCrlException {
    try {
      return X509Util.parseBcCert(certFile);
    } catch (CertificateException | IOException ex) {
      throw new ImportCrlException("could not parse X.509 certificate from file "
          + certFile + ": " + ex.getMessage(), ex);
    }
  }

  private Long getId(int caId, BigInteger serialNumber) throws DataAccessException {
    ResultSet rs = null;
    try {
      psSelectIdCert.setInt(1, caId);
      psSelectIdCert.setString(2, serialNumber.toString(16));
      rs = psSelectIdCert.executeQuery();
      if (!rs.next()) {
        return null;
      }
      return rs.getLong("ID");
    } catch (SQLException ex) {
      throw datasource.translate(sqlSelectIdCert, ex);
    } finally {
      releaseResources(null, rs);
    }
  }

  private void addCertificate(AtomicLong maxId, int caId, Certificate cert, String profileName,
      String certLogId) throws DataAccessException, ImportCrlException {
    // not issued by the given issuer
    if (!caSubject.equals(cert.getIssuer())) {
      LOG.warn("certificate {} is not issued by the given CA, ignore it", certLogId);
      return;
    }

    // we don't use the binary read from file, since it may contains redundant ending bytes.
    byte[] encodedCert;
    try {
      encodedCert = cert.getEncoded();
    } catch (IOException ex) {
      throw new ImportCrlException("could not encode certificate {}" + certLogId, ex);
    }
    String b64CertHash = certhashAlgo.base64Hash(encodedCert);

    if (caSpki != null) {
      byte[] aki = null;
      try {
        aki = X509Util.extractAki(cert);
      } catch (CertificateEncodingException ex) {
        LogUtil.error(LOG, ex,
            "invalid AuthorityKeyIdentifier of certificate {}" + certLogId + ", ignore it");
        return;
      }

      if (aki == null || !Arrays.equals(caSpki, aki)) {
        LOG.warn("certificate {} is not issued by the given CA, ignore it", certLogId);
        return;
      }
    } // end if

    LOG.info("Importing certificate {}", certLogId);
    Long id = getId(caId, cert.getSerialNumber().getPositiveValue());
    boolean tblCertIdExists = (id != null);

    PreparedStatement ps;
    String sql;
    // first update the table CERT
    if (tblCertIdExists) {
      sql = SQL_UPDATE_CERT;
      ps = psUpdateCert;
    } else {
      sql = SQL_INSERT_CERT;
      ps = psInsertCert;
      id = maxId.incrementAndGet();
    }

    try {
      int offset = 1;
      if (sql == SQL_INSERT_CERT) {
        ps.setLong(offset++, id);
        // ISSUER ID IID
        ps.setInt(offset++, caId);
        // serial number SN
        ps.setString(offset++, cert.getSerialNumber().getPositiveValue().toString(16));
        // whether revoked REV
        ps.setInt(offset++, 0);
        // revocation reason RR
        ps.setNull(offset++, Types.SMALLINT);
        // revocation time RT
        ps.setNull(offset++, Types.BIGINT);
        ps.setNull(offset++, Types.BIGINT);
      }

      // last update LUPDATE
      ps.setLong(offset++, System.currentTimeMillis() / 1000);

      TBSCertificate tbsCert = cert.getTBSCertificate();
      // not before NBEFORE
      ps.setLong(offset++, tbsCert.getStartDate().getDate().getTime() / 1000);
      // not after NAFTER
      ps.setLong(offset++, tbsCert.getEndDate().getDate().getTime() / 1000);
      ps.setString(offset++, b64CertHash);

      if (sql == SQL_UPDATE_CERT) {
        ps.setLong(offset++, id);
      }

      ps.executeUpdate();
    } catch (SQLException ex) {
      throw datasource.translate(sql, ex);
    }

    LOG.info("Imported  certificate {}", certLogId);
  }

  private void addCertificateBySerialNumber(AtomicLong maxId, int caId, BigInteger serialNumber)
      throws DataAccessException {
    LOG.info("Importing certificate by serial number {}", serialNumber);
    Long id = getId(caId, serialNumber);
    boolean tblCertIdExists = (id != null);

    PreparedStatement ps;
    String sql;
    // first update the table CERT
    if (tblCertIdExists) {
      sql = SQL_UPDATE_CERT;
      ps = psUpdateCert;
    } else {
      sql = SQL_INSERT_CERT;
      ps = psInsertCert;
      id = maxId.incrementAndGet();
    }

    try {
      int offset = 1;
      if (sql == SQL_INSERT_CERT) {
        ps.setLong(offset++, id);
        // ISSUER ID IID
        ps.setInt(offset++, caId);
        // serial number SN
        ps.setString(offset++, serialNumber.toString(16));
        // whether revoked REV
        ps.setInt(offset++, 0);
        // revocation reason RR
        ps.setNull(offset++, Types.SMALLINT);
        // revocation time RT
        ps.setNull(offset++, Types.BIGINT);
        ps.setNull(offset++, Types.BIGINT);
      }

      // last update LUPDATE
      ps.setLong(offset++, System.currentTimeMillis() / 1000);

      // not before NBEFORE, we use the minimal time
      ps.setLong(offset++, 0);
      // not after NAFTER, use Long.MAX_VALUE
      ps.setLong(offset++, Long.MAX_VALUE);
      ps.setString(offset++, null);

      if (sql == SQL_UPDATE_CERT) {
        ps.setLong(offset++, id);
      }

      ps.executeUpdate();
    } catch (SQLException ex) {
      throw datasource.translate(sql, ex);
    }

    LOG.info(" Imported certificate by serial number {}", serialNumber);
  }

  private void deleteEntriesNotUpdatedSince(Connection conn, Date time) throws DataAccessException {
    // remove the unmodified entries
    String sql = "DELETE FROM CERT WHERE LUPDATE<" + time.getTime() / 1000;
    Statement stmt = datasource.createStatement(conn);
    try {
      stmt.executeUpdate(sql);
    } catch (SQLException ex) {
      throw datasource.translate(sql, ex);
    } finally {
      releaseResources(stmt, null);
    }
  }

  private void releaseResources(Statement ps, ResultSet rs) {
    datasource.releaseResources(ps, rs, false);
  }

}
