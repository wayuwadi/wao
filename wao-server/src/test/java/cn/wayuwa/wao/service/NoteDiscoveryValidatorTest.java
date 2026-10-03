package cn.wayuwa.wao.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cn.wayuwa.wao.domain.DocEntity;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NoteDiscoveryValidatorTest {
    private final ObjectMapper json=new ObjectMapper();
    private final NoteDiscoveryValidator validator=new NoteDiscoveryValidator(json);
    private final List<NoteDiscoveryValidator.Material> materials=NoteDiscoveryAiEvaluationTest.materials(List.of("同一批客户名称又核对了一遍。","下周还要核对这批客户名称。"));
    private ObjectNode valid() throws Exception {
        return (ObjectNode)json.readTree("""
            {"schemaVersion":1,"candidates":[{"candidateId":"c1","kind":"REPEATED_FRICTION",
            "headline":"同一批客户名称重复核对","observation":"两篇记录都涉及同一批客户名称核对",
            "valueReason":"已经确认的对照材料可以复用","optionalNextStep":null,"topicKey":"客户名称核对",
            "claims":[{"id":"s1","text":"两篇涉及同一批客户"}],
            "evidence":[{"docId":"synthetic-1","docRev":1,"quote":"同一批客户名称又核对了一遍。","supports":["s1"]},
            {"docId":"synthetic-2","docRev":1,"quote":"下周还要核对这批客户名称。","supports":["s1"]}],"checkedCounterEvidence":[]}],"noFindingReason":null}
            """);
    }
    @Test void internalProvenanceCannotBecomeUserExplanation() throws Exception { var out=valid(); ((ObjectNode)out.at("/candidates/0")).put("valueReason","材料整理沿革为 LEGACY_UNKNOWN");assertThat(validator.validate(out.toString(),materials).rejected()).containsExactly("c1:INTERNAL_DETAILS"); }
    @Test void allowsEmptySuccessfulOutput() { assertThat(validator.validate("{\"schemaVersion\":1,\"candidates\":[]}",materials).candidates()).isEmpty(); }
    @Test void keepsGroundedCrossDocumentRelationship() throws Exception { assertThat(validator.validate(valid().toString(),materials).candidates()).hasSize(1); }
    @Test void rejectsInventedSourceWithoutFallback() throws Exception { var out=valid();((ObjectNode)out.at("/candidates/0/evidence/0")).put("docId","another-member");assertThat(validator.validate(out.toString(),materials).candidates()).isEmpty(); }
    @Test void rejectsForgedQuote() throws Exception { var out=valid();((ObjectNode)out.at("/candidates/0/evidence/0")).put("quote","已经节省20小时");assertThat(validator.validate(out.toString(),materials).rejected()).containsExactly("c1:INVALID_EVIDENCE"); }
    @Test void rejectsStaleRevision() throws Exception { var out=valid();((ObjectNode)out.at("/candidates/0/evidence/1")).put("docRev",2);assertThat(validator.validate(out.toString(),materials).candidates()).isEmpty(); }
    @Test void sameDocumentCannotBecomeTwoOccurrences() throws Exception { var out=valid();var e=(ObjectNode)out.at("/candidates/0/evidence/1");e.put("docId","synthetic-1").put("quote",materials.get(0).body());assertThat(validator.validate(out.toString(),materials).candidates()).isEmpty(); }
    @Test void missingClaimSupportRejectsCandidate() throws Exception { var out=valid();out.at("/candidates/0/claims").withArray("");((ObjectNode)out.at("/candidates/0/claims/0")).put("id","s2");assertThat(validator.validate(out.toString(),materials).candidates()).isEmpty(); }
    @Test void invalidCounterEvidenceRejectsCandidate() throws Exception { var out=valid();((ObjectNode)out.at("/candidates/0")).withArray("checkedCounterEvidence").addObject().put("docId","forged").put("docRev",1).put("quote","不用再核对");assertThat(validator.validate(out.toString(),materials).candidates()).isEmpty(); }
    @Test void brokenProtocolIsNotEmptySuccess() { for(String text:List.of("not json","{}","null","{\"schemaVersion\":1,\"candidates\":{}}"))assertThatThrownBy(()->validator.validate(text,materials)).isInstanceOf(IllegalArgumentException.class); }
    @Test void overlongFieldsAreNotSilentlyTruncated() throws Exception { var out=valid();((ObjectNode)out.at("/candidates/0")).put("headline","长".repeat(101));assertThatThrownBy(()->validator.validate(out.toString(),materials)).hasMessage("PROTOCOL_ERROR"); }
    @Test void duplicateCandidateIdsFailProtocol() throws Exception { var out=valid();out.withArray("candidates").add(out.at("/candidates/0").deepCopy());assertThatThrownBy(()->validator.validate(out.toString(),materials)).hasMessage("PROTOCOL_ERROR"); }
    @Test void permissionsComeFromServerMaterials() throws Exception { assertThat(validator.validate(valid().toString(),List.of(materials.get(0))).candidates()).isEmpty(); }
    @Test void normalizedBodyPreservesNegationNumbersAndEnding() {
        assertThat(NoteDiscoveryIndexService.normalize("\r\n不是 1800 字  \r\n已经解决\t \r\n")).isEqualTo("不是 1800 字\n已经解决");
        assertThat(NoteDiscoveryIndexService.hash("完成")).isNotEqualTo(NoteDiscoveryIndexService.hash("未完成"));
    }
    @Test void legacyBlocksAndRichDocumentOnlyExtractText() {
        var index=new NoteDiscoveryIndexService(json);var doc=new DocEntity();doc.setBlocksJson("[{\"p\":\"不是1800字\",\"url\":\"secret-url\"},{\"ul\":[\"已经解决\"]}]");
        assertThat(index.text(doc)).isEqualTo("不是1800字\n已经解决");
        doc.setContentJson("{\"version\":1,\"document\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"没有复发\"}]}]}}");assertThat(index.text(doc)).isEqualTo("没有复发");
    }
    @Test void imageMetadataDoesNotBecomeNewFact() {
        var index=new NoteDiscoveryIndexService(json);var doc=new DocEntity();doc.setContentMarkdown("![文章总览图](/api/images/a)\n正文没有复发");assertThat(index.text(doc)).isEqualTo("正文没有复发");
    }
    @Test void synonymsRecallOldExperienceAndLateSolution() {
        var index=new NoteDiscoveryIndexService(json);assertThat(index.terms("出差打包清单")).contains("收拾行李","打包");assertThat(index.terms("客户简称与全称已经对齐")).contains("名称","对齐");
    }
    private String review(String counter,String matched) {return "{\"schemaVersion\":1,\"reviews\":[{\"candidateId\":\"c1\",\"decision\":\"KEEP\",\"reasons\":[],\"unsupportedClaimIds\":[],\"matchedExistingItemId\":"+matched+",\"topicState\":\"OPEN\",\"counterEvidenceDocIds\":"+counter+"}]}";}
    @Test void acceptsStrictReview() throws Exception {assertThat(validator.validateReviews(review("[]","null"),valid().path("candidates"),materials,json.createArrayNode())).containsKey("c1");}
    @Test void reviewCannotInventCounterSourceOrOtherMemberMatch() throws Exception {
        for(String response:List.of(review("[\"other-member\"]","null"),review("[]","\"foreign-item\"")))assertThatThrownBy(()->validator.validateReviews(response,valid().path("candidates"),materials,json.createArrayNode())).hasMessage("PROTOCOL_ERROR");
    }
    @Test void duplicateJsonKeysAndTrailingPayloadAreProtocolFailures() {for(String response:List.of("{\"candidates\":[],\"candidates\":[]}","{} {}"))assertThatThrownBy(()->validator.parse(response)).hasMessage("PROTOCOL_ERROR");}
}
