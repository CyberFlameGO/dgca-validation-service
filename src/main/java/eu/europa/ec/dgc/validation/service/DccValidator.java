package eu.europa.ec.dgc.validation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dgca.verifier.app.decoder.base45.Base45Service;
import dgca.verifier.app.decoder.base45.DefaultBase45Service;
import dgca.verifier.app.decoder.cbor.CborService;
import dgca.verifier.app.decoder.cbor.DefaultCborService;
import dgca.verifier.app.decoder.cbor.GreenCertificateData;
import dgca.verifier.app.decoder.compression.CompressorService;
import dgca.verifier.app.decoder.compression.DefaultCompressorService;
import dgca.verifier.app.decoder.cose.CoseService;
import dgca.verifier.app.decoder.cose.CryptoService;
import dgca.verifier.app.decoder.cose.DefaultCoseService;
import dgca.verifier.app.decoder.cose.VerificationCryptoService;
import dgca.verifier.app.decoder.model.*;
import dgca.verifier.app.decoder.prefixvalidation.DefaultPrefixValidationService;
import dgca.verifier.app.decoder.prefixvalidation.PrefixValidationService;
import dgca.verifier.app.decoder.schema.DefaultSchemaValidator;
import dgca.verifier.app.decoder.schema.SchemaValidator;
import dgca.verifier.app.decoder.services.X509;
import dgca.verifier.app.engine.CertLogicEngine;
import dgca.verifier.app.engine.DateTimeKt;
import dgca.verifier.app.engine.ValidationResult;
import dgca.verifier.app.engine.data.CertificateType;
import dgca.verifier.app.engine.data.ExternalParameter;
import dgca.verifier.app.engine.data.Rule;
import dgca.verifier.app.engine.data.Type;
import dgca.verifier.app.engine.data.source.remote.rules.RuleRemote;
import dgca.verifier.app.engine.data.source.remote.rules.RuleRemoteMapperKt;
import dgca.verifier.app.engine.data.source.remote.valuesets.ValueSetRemote;
import eu.europa.ec.dgc.utils.CertificateUtils;
import eu.europa.ec.dgc.validation.entity.BusinessRuleEntity;
import eu.europa.ec.dgc.validation.entity.ValueSetEntity;
import eu.europa.ec.dgc.validation.exception.DccException;
import eu.europa.ec.dgc.validation.restapi.dto.*;

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class DccValidator {
    private PrefixValidationService prefixValidationService = new DefaultPrefixValidationService();
    private Base45Service base45Service = new DefaultBase45Service();
    private CompressorService compressorService = new DefaultCompressorService();
    private CoseService coseService = new DefaultCoseService();
    private CborService cborService = new DefaultCborService();
    private SchemaValidator schemaValidator = new DefaultSchemaValidator();
    private X509 x509 = new X509();
    private CryptoService cryptoService = new VerificationCryptoService(x509);
    private final SignerInformationService signerInformationService;
    private final CertLogicEngine certLogicEngine;
    private final CertificateUtils certificateUtils;
    private final ValueSetCache valueSetCache;
    private final RulesCache rulesCache;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ZoneId UTC_ZONE_ID = ZoneId.ofOffset("", ZoneOffset.UTC).normalized();

    @PostConstruct
    public void initMapper() {
        objectMapper.registerModule(new JavaTimeModule());
    }

    public List<ValidationStatusResponse.Result> validate(String dcc, AccessTokenConditions accessTokenConditions, AccessTokenType accessTokenType) {
        List<ValidationStatusResponse.Result> results = new ArrayList<>();

        VerificationResult verificationResult = new VerificationResult();
        String dccPlain = prefixValidationService.decode(dcc,verificationResult);
        if (verificationResult.getContextPrefix()==null) {
            addResult(results, ValidationStatusResponse.Result.ResultType.NOK,
                    ValidationStatusResponse.Result.Type.FAILED,ResultTypeIdentifier.TechnicalVerification,"No HC1: prefix");
            return results;
        }
        byte[] compressedCose = base45Service.decode(dccPlain, verificationResult);
        if (!verificationResult.getBase45Decoded()) {
            addResult(results, ValidationStatusResponse.Result.ResultType.NOK,
                    ValidationStatusResponse.Result.Type.FAILED,ResultTypeIdentifier.TechnicalVerification,"Wrong Base45 coding");
            return results;
        }
        byte[] cose = compressorService.decode(compressedCose, verificationResult);
        if (cose==null || !verificationResult.getZlibDecoded()) {
            addResult(results, ValidationStatusResponse.Result.ResultType.NOK,
                    ValidationStatusResponse.Result.Type.FAILED,ResultTypeIdentifier.TechnicalVerification,"Can not decompress data");
            return results;
        }
        CoseData coseData = coseService.decode(cose, verificationResult);
        if (coseData==null) {
            addResult(results, ValidationStatusResponse.Result.ResultType.NOK,
                    ValidationStatusResponse.Result.Type.FAILED,ResultTypeIdentifier.TechnicalVerification,"Can not decode cose");
            return results;
        }
        if (coseData.getKid()==null) {
            addResult(results, ValidationStatusResponse.Result.ResultType.NOK,
                    ValidationStatusResponse.Result.Type.FAILED,ResultTypeIdentifier.TechnicalVerification,"Can not extract kid");
            return results;
        }
        schemaValidator.validate(coseData.getCbor(),verificationResult);
        if (!verificationResult.isSchemaValid()) {
            addResult(results, ValidationStatusResponse.Result.ResultType.NOK,
                    ValidationStatusResponse.Result.Type.FAILED,ResultTypeIdentifier.TechnicalVerification,"schema invalid");
            return results;
        }
        GreenCertificateData greenCertificateData = cborService.decodeData(coseData.getCbor(), verificationResult);
        if (!verificationResult.getCborDecoded()) {
            addResult(results, ValidationStatusResponse.Result.ResultType.NOK,
                    ValidationStatusResponse.Result.Type.FAILED,ResultTypeIdentifier.TechnicalVerification,"can not decode cbor");
            return results;
        }
        addResult(results, ValidationStatusResponse.Result.ResultType.OK,
                ValidationStatusResponse.Result.Type.PASSED, ResultTypeIdentifier.TechnicalVerification, "OK");
        if (accessTokenType==AccessTokenType.Structure) {
            if (accessTokenConditions.getHash()==null || accessTokenConditions.getHash().length()==0) {
                addResult(results, ValidationStatusResponse.Result.ResultType.NOK,
                        ValidationStatusResponse.Result.Type.FAILED, ResultTypeIdentifier.TechnicalVerification, "dcc hash not provided for check type 0");
            } else {
                try {
                    if (!certificateUtils.calculateHash(dcc.getBytes(StandardCharsets.UTF_8)).equals(accessTokenConditions.getHash())) {
                        addResult(results, ValidationStatusResponse.Result.ResultType.NOK,
                                    ValidationStatusResponse.Result.Type.FAILED, ResultTypeIdentifier.TechnicalVerification, "dcc hash does not match");
                    }
                } catch (NoSuchAlgorithmException e) {
                    throw new DccException("hash calculation",e);
                }
            }
        }
        checkExpirationDates(greenCertificateData, accessTokenConditions, results);
        checkAcceptableCertType(greenCertificateData, accessTokenConditions, results);
        if (accessTokenType.intValue()>AccessTokenType.Structure.intValue()) {
            validateGreenCertificateNameDob(greenCertificateData, accessTokenConditions, results);
            validateCryptographic(cose, coseData.getKid(), accessTokenConditions, verificationResult, results);
            if (accessTokenType==AccessTokenType.Full) {
                validateRules(greenCertificateData, verificationResult, results, accessTokenConditions, coseData.getKid());
            }
        }
        if (results.isEmpty()) {
            addResult(results, ValidationStatusResponse.Result.ResultType.OK,
                    ValidationStatusResponse.Result.Type.PASSED, ResultTypeIdentifier.TechnicalVerification, "OK");
        }

        return results;
    }

    private void checkExpirationDates(GreenCertificateData greenCertificateData, AccessTokenConditions accessTokenConditions,
                                      List<ValidationStatusResponse.Result> results) {
        ZonedDateTime validFrom = ZonedDateTime.parse(accessTokenConditions.getValidFrom());
        ZonedDateTime validTo = ZonedDateTime.parse(accessTokenConditions.getValidTo());
        if (!greenCertificateData.getExpirationTime().isAfter(validTo)) {
            addResult(results, ValidationStatusResponse.Result.ResultType.NOK,
                    ValidationStatusResponse.Result.Type.FAILED, ResultTypeIdentifier.TechnicalVerification, "Dcc exp date before validTo");
        }
        if (greenCertificateData.getGreenCertificate().getType() == dgca.verifier.app.decoder.model.CertificateType.RECOVERY) {
            Test testStatement = greenCertificateData.getGreenCertificate().getTests().get(0);
            ZonedDateTime dateOfCollection = toZonedDateTimeOrUtcLocal(testStatement.getDateTimeOfCollection());
            if (dateOfCollection!=null && !dateOfCollection.isBefore(dateOfCollection)) {
                addResult(results, ValidationStatusResponse.Result.ResultType.NOK,
                        ValidationStatusResponse.Result.Type.FAILED, ResultTypeIdentifier.TechnicalVerification, "Test collection date after condition validFrom");
            }
        } else if (greenCertificateData.getGreenCertificate().getType() == dgca.verifier.app.decoder.model.CertificateType.RECOVERY) {
            RecoveryStatement recoveryStatement = greenCertificateData.getGreenCertificate().getRecoveryStatements().get(0);
            ZonedDateTime certValidFrom = toZonedDateTimeOrUtcLocal(recoveryStatement.getCertificateValidFrom());
            ZonedDateTime certValidTo = toZonedDateTimeOrUtcLocal(recoveryStatement.getCertificateValidUntil());
            if (certValidFrom!=null && !certValidFrom.isBefore(validFrom)) {
                addResult(results, ValidationStatusResponse.Result.ResultType.NOK,
                        ValidationStatusResponse.Result.Type.FAILED, ResultTypeIdentifier.TechnicalVerification, "Recovery validFrom after condition validFrom");
            }
            if (certValidTo!=null && !certValidFrom.isAfter(validTo)) {
                addResult(results, ValidationStatusResponse.Result.ResultType.NOK,
                        ValidationStatusResponse.Result.Type.FAILED, ResultTypeIdentifier.TechnicalVerification, "Recovery validTo before condition validTo");
            }
        }
    }

    ZonedDateTime toZonedDateTimeOrUtcLocal(String dateTime) {
        ZonedDateTime zonedDateTime;
        try {
            zonedDateTime = ZonedDateTime.parse(dateTime).withZoneSameInstant(UTC_ZONE_ID);
        } catch (DateTimeParseException dateTimeParseException) {
            try {
                zonedDateTime = LocalDateTime.parse(dateTime).atZone(UTC_ZONE_ID);
            } catch (DateTimeParseException dateTimeParseException1) {
                try {
                    zonedDateTime = LocalDate.parse(dateTime).atStartOfDay(UTC_ZONE_ID);
                } catch (DateTimeParseException dateTimeParseException2) {
                    zonedDateTime = null;
                }
            }
        }
        return zonedDateTime;
    }

    private void validateRules(GreenCertificateData greenCertificateData, VerificationResult verificationResult,
                               List<ValidationStatusResponse.Result> results, AccessTokenConditions accessTokenConditions, byte[] kid) {
        String countryOfArrival = accessTokenConditions.getCoa();
        List<Rule> rules = rulesCache.provideRules(countryOfArrival);
        if (rules!=null && rules.size()>0) {
            ZonedDateTime validationClock = ZonedDateTime.parse(accessTokenConditions.getValidationClock());
            String kidBase64 = Base64.getEncoder().encodeToString(kid);
            Map<String, List<String>> valueSets = valueSetCache.provideValueSets();
            ExternalParameter externalParameter = new ExternalParameter(validationClock, valueSets, countryOfArrival,
                    greenCertificateData.getExpirationTime(),
                    greenCertificateData.getIssuedAt(),
                    greenCertificateData.getIssuingCountry(),
                    kidBase64,
                    accessTokenConditions.getRoa()
                    );
            String hcertJson = greenCertificateData.getHcertJson();
            CertificateType certEngineType;
            switch (greenCertificateData.getGreenCertificate().getType()) {
                case RECOVERY:
                    certEngineType = CertificateType.RECOVERY;
                    break;
                case VACCINATION:
                    certEngineType = CertificateType.VACCINATION;
                    break;
                default:
                    certEngineType = CertificateType.TEST;
            }
            List<ValidationResult> ruleValidationResults = certLogicEngine.validate(certEngineType, greenCertificateData.getGreenCertificate().getSchemaVersion(),
                    rules, externalParameter, hcertJson);
            for (ValidationResult validationResult : ruleValidationResults) {
                ValidationStatusResponse.Result.Type type;
                ValidationStatusResponse.Result.ResultType resultType;
                switch (validationResult.getResult()) {
                    case OPEN:
                        type = ValidationStatusResponse.Result.Type.OPEN;
                        resultType = ValidationStatusResponse.Result.ResultType.NOK;
                        break;
                    case PASSED:
                        type = ValidationStatusResponse.Result.Type.PASSED;
                        resultType = ValidationStatusResponse.Result.ResultType.OK;
                        break;
                    default:
                        type = ValidationStatusResponse.Result.Type.FAILED;
                        resultType = ValidationStatusResponse.Result.ResultType.NOK;
                        break;
                }
                StringBuilder details = new StringBuilder();
                details.append(validationResult.getRule().getIdentifier()).append(' ');
                details.append(validationResult.getRule().getDescriptionFor("en")).append(' ');
                if (validationResult.getCurrent()!=null && validationResult.getCurrent().length()>0) {
                    details.append(validationResult.getCurrent()).append(' ');
                }
                if (validationResult.getValidationErrors()!=null && validationResult.getValidationErrors().size()>0) {
                    details.append(" Exceptions: ");
                    for (Exception exception : validationResult.getValidationErrors()) {
                        details.append(exception.getMessage()).append(' ');
                    }
                }
                ResultTypeIdentifier resultTypeIdentifier;
                if (validationResult.getRule()!=null)  {
                    if (validationResult.getRule().getType()== Type.INVALIDATION) {
                        resultTypeIdentifier = ResultTypeIdentifier.IssuerInvalidation;
                    } else if (validationResult.getRule().getType() == Type.ACCEPTANCE) {
                        resultTypeIdentifier = ResultTypeIdentifier.DestinationAcceptance;
                        // TODO sub type General TravellerAcceptance
                        // resultTypeIdentifier = ResultTypeIdentifier.TravellerAcceptance
                    } else {
                        resultTypeIdentifier = ResultTypeIdentifier.IssuerInvalidation;
                    }
                } else {
                    resultTypeIdentifier = ResultTypeIdentifier.IssuerInvalidation;
                }
                addResult(results, resultType, type, resultTypeIdentifier, details.toString());
            }
        } else {
            addResult(results, ValidationStatusResponse.Result.ResultType.OK,
                    ValidationStatusResponse.Result.Type.PASSED, ResultTypeIdentifier.IssuerInvalidation, "No rules for country of departure defined");
        }
    }

    private void validateCryptographic(byte[] cose, byte[] kid, AccessTokenConditions accessTokenConditions, VerificationResult verificationResult, List<ValidationStatusResponse.Result> results) {
        ZonedDateTime validationClock = ZonedDateTime.parse(accessTokenConditions.getValidationClock());
        String kidBase64 = Base64.getEncoder().encodeToString(kid);
        List<Certificate> certificates = signerInformationService.getCertificates(kidBase64);
        if (certificates!=null && certificates.size()>0) {
            boolean signValidated = false;
            for (Certificate certificate : certificates) {
                cryptoService.validate(cose, certificate, verificationResult);
                if (verificationResult.getCoseVerified()) {
                    ZonedDateTime expirationTime = (certificate instanceof X509Certificate) ?
                            ((X509Certificate) certificate).getNotAfter().toInstant().atZone(DateTimeKt.getUTC_ZONE_ID())
                            : null;
                    if (expirationTime != null && validationClock.isAfter(expirationTime)) {
                        addResult(results, ValidationStatusResponse.Result.ResultType.NOK,
                                ValidationStatusResponse.Result.Type.FAILED, ResultTypeIdentifier.TechnicalVerification,
                                "certificate expired for validation clock");
                    }
                    signValidated = true;
                    break;
                }
            }
            if (!signValidated) {
                addResult(results, ValidationStatusResponse.Result.ResultType.NOK,
                        ValidationStatusResponse.Result.Type.FAILED, ResultTypeIdentifier.TechnicalVerification, "signature invalid");
            } else {
                addResult(results, ValidationStatusResponse.Result.ResultType.OK,
                        ValidationStatusResponse.Result.Type.PASSED, ResultTypeIdentifier.TechnicalVerification, "signature valid");
            }
        } else {
            addResult(results, ValidationStatusResponse.Result.ResultType.NOK,
                    ValidationStatusResponse.Result.Type.FAILED, ResultTypeIdentifier.TechnicalVerification, "unknown dcc signing kid");
        }
    }

    private void validateGreenCertificateNameDob(GreenCertificateData greenCertificateData, AccessTokenConditions accessTokenConditions, List<ValidationStatusResponse.Result> results) {
        if (greenCertificateData.getGreenCertificate().getPerson().getStandardisedFamilyName()==null ||
        !greenCertificateData.getGreenCertificate().getPerson().getStandardisedFamilyName().equals(accessTokenConditions.getFnt())) {
            addResult(results, ValidationStatusResponse.Result.ResultType.NOK,
                    ValidationStatusResponse.Result.Type.FAILED,ResultTypeIdentifier.TechnicalVerification,"family name does not match");
        }
        if (greenCertificateData.getGreenCertificate().getPerson().getStandardisedGivenName()==null ||
                !greenCertificateData.getGreenCertificate().getPerson().getStandardisedGivenName().equals(accessTokenConditions.getGnt())) {
            addResult(results, ValidationStatusResponse.Result.ResultType.NOK,
                    ValidationStatusResponse.Result.Type.FAILED,ResultTypeIdentifier.TechnicalVerification,"given name does not match");
        }
        if (greenCertificateData.getGreenCertificate().getDateOfBirth()==null ||
                !greenCertificateData.getGreenCertificate().getDateOfBirth().equals(accessTokenConditions.getDob())) {
            addResult(results, ValidationStatusResponse.Result.ResultType.NOK,
                    ValidationStatusResponse.Result.Type.FAILED,ResultTypeIdentifier.TechnicalVerification,"data of birth does not match");
        }
    }

    private void checkAcceptableCertType(GreenCertificateData greenCertificateData, AccessTokenConditions accessTokenConditions, List<ValidationStatusResponse.Result> results) {
        if (accessTokenConditions.getType()!=null) {
            boolean accepted = false;
            for (String acceptableTypeSymbol : accessTokenConditions.getType()) {
                AcceptableType acceptableType = AcceptableType.getTokenForInt(acceptableTypeSymbol);
                switch (acceptableType) {
                    case Vaccination:
                        accepted = greenCertificateData.getGreenCertificate().getType() == dgca.verifier.app.decoder.model.CertificateType.VACCINATION;
                        break;
                    case Recovery:
                        accepted = greenCertificateData.getGreenCertificate().getType() == dgca.verifier.app.decoder.model.CertificateType.RECOVERY;
                        break;
                    case Test:
                        accepted = greenCertificateData.getGreenCertificate().getType() == dgca.verifier.app.decoder.model.CertificateType.TEST;
                        break;
                    case PCRTest:
                        accepted = isPcrTest(greenCertificateData.getGreenCertificate());
                        break;
                    case RATTest:
                        accepted = isRatTest(greenCertificateData.getGreenCertificate());
                        break;
                }
                if (accepted) {
                    break;
                }
            }
            if (!accepted) {
                addResult(results, ValidationStatusResponse.Result.ResultType.NOK,
                        ValidationStatusResponse.Result.Type.FAILED,ResultTypeIdentifier.TechnicalVerification,"required acceptable cert type not provided");
            }
        }
    }

    private boolean isRatTest(GreenCertificate greenCertificate) {
        boolean ratTest;
        if (greenCertificate.getType()== dgca.verifier.app.decoder.model.CertificateType.TEST
          && greenCertificate.getTests()!=null && greenCertificate.getTests().size()>0
        ) {
            ratTest = AcceptableType.RAPID_TEST_TYPE.equals(greenCertificate.getTests().get(0).getTypeOfTest());
        } else {
            ratTest = false;
        }
        return ratTest;
    }

    private boolean isPcrTest(GreenCertificate greenCertificate) {
        boolean pcrTest;
        if (greenCertificate.getType()== dgca.verifier.app.decoder.model.CertificateType.TEST
                && greenCertificate.getTests()!=null && greenCertificate.getTests().size()>0) {
            pcrTest = AcceptableType.PCR_TEST_TYPE.equals(greenCertificate.getTests().get(0).getTypeOfTest());
        } else {
            pcrTest = false;
        }
        return pcrTest;
    }

    private void addResult(List<ValidationStatusResponse.Result> results, ValidationStatusResponse.Result.ResultType resultType,
                      ValidationStatusResponse.Result.Type type, ResultTypeIdentifier identifier, String details) {
        ValidationStatusResponse.Result result = new ValidationStatusResponse.Result();
        result.setResult(resultType);
        result.setType(type);
        result.setIdentifier(identifier.getIdentifier());
        result.setDetails(details);
        results.add(result);
    }
}
