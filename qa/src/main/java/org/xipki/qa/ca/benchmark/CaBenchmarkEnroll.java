/*
 *
 * Copyright (c) 2013 - 2018 Lijun Liao
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

package org.xipki.qa.ca.benchmark;

import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.bouncycastle.asn1.crmf.CertRequest;
import org.bouncycastle.asn1.crmf.CertTemplate;
import org.bouncycastle.asn1.crmf.CertTemplateBuilder;
import org.bouncycastle.asn1.crmf.ProofOfPossession;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.cmpclient.CmpClient;
import org.xipki.cmpclient.CmpClientException;
import org.xipki.cmpclient.EnrollCertRequest;
import org.xipki.cmpclient.EnrollCertRequest.EnrollType;
import org.xipki.cmpclient.EnrollCertResult;
import org.xipki.cmpclient.EnrollCertResult.CertifiedKeyPairOrError;
import org.xipki.cmpclient.PkiErrorException;
import org.xipki.util.Args;
import org.xipki.util.BenchmarkExecutor;

/**
 * TODO.
 * @author Lijun Liao
 * @since 2.0.0
 */

public class CaBenchmarkEnroll extends BenchmarkExecutor {

  private static final ProofOfPossession RA_VERIFIED = new ProofOfPossession();

  private static final Logger LOG = LoggerFactory.getLogger(CaBenchmarkEnroll.class);

  private final CmpClient client;

  private final BenchmarkEntry benchmarkEntry;

  private final AtomicLong index;

  private final int num;

  private final int maxRequests;

  private AtomicInteger processedRequests = new AtomicInteger(0);

  public CaBenchmarkEnroll(CmpClient client, BenchmarkEntry benchmarkEntry, int maxRequests,
      int num, String description) {
    super(description);
    this.maxRequests = maxRequests;
    this.num = Args.positive(num, "num");
    this.benchmarkEntry = Args.notNull(benchmarkEntry, "benchmarkEntry");
    this.client = Args.notNull(client, "client");
    this.index = new AtomicLong(getSecureIndex());
  }

  class Testor implements Runnable {
    @Override
    public void run() {
      while (!stop() && getErrorAccout() < 1) {
        Map<Integer, CertRequest> certReqs = nextCertRequests();
        if (certReqs == null) {
          break;
        }

        boolean successful = testNext(certReqs);
        int numFailed = successful ? 0 : 1;
        account(1, numFailed);
      }
    }

    private boolean testNext(Map<Integer, CertRequest> certRequests) {
      EnrollCertResult result;
      try {
        EnrollCertRequest request = new EnrollCertRequest(EnrollType.CERT_REQ);
        for (Integer certId : certRequests.keySet()) {
          EnrollCertRequest.Entry requestEntry = new EnrollCertRequest.Entry("id-" + certId,
              benchmarkEntry.getCertprofile(), certRequests.get(certId), RA_VERIFIED);
          request.addRequestEntry(requestEntry);
        }

        result = client.enrollCerts(null, request, null);
      } catch (CmpClientException | PkiErrorException ex) {
        LOG.warn("{}: {}", ex.getClass().getName(), ex.getMessage());
        return false;
      } catch (Throwable th) {
        LOG.warn("{}: {}", th.getClass().getName(), th.getMessage());
        return false;
      }

      if (result == null) {
        return false;
      }

      Set<String> ids = result.getAllIds();
      int numSuccess = 0;
      for (String id : ids) {
        CertifiedKeyPairOrError certOrError = result.getCertOrError(id);
        X509Certificate cert = (X509Certificate) certOrError.getCertificate();

        if (cert != null) {
          numSuccess++;
        }
      }

      return numSuccess == certRequests.size();
    } // method testNext

  } // class Testor

  @Override
  protected Runnable getTestor() throws Exception {
    return new Testor();
  }

  @Override
  protected int getRealAccount(int account) {
    return num * account;
  }

  private Map<Integer, CertRequest> nextCertRequests() {
    if (maxRequests > 0) {
      int num = processedRequests.getAndAdd(1);
      if (num >= maxRequests) {
        return null;
      }
    }

    Map<Integer, CertRequest> certRequests = new HashMap<>();
    for (int i = 0; i < num; i++) {
      final int certId = i + 1;
      CertTemplateBuilder certTempBuilder = new CertTemplateBuilder();

      long thisIndex = index.getAndIncrement();
      certTempBuilder.setSubject(benchmarkEntry.getX500Name(thisIndex));

      SubjectPublicKeyInfo spki = benchmarkEntry.getSubjectPublicKeyInfo();
      certTempBuilder.setPublicKey(spki);
      CertTemplate certTemplate = certTempBuilder.build();
      CertRequest certRequest = new CertRequest(certId, certTemplate, null);
      certRequests.put(certId, certRequest);
    }
    return certRequests;
  }

}
