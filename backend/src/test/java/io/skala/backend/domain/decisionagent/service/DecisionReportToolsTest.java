package io.skala.backend.domain.decisionagent.service;

import static io.skala.backend.domain.decisionagent.dto.DecisionAgentDtos.*;
import static io.skala.backend.domain.decisionagent.dto.DecisionAssistantDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.skala.backend.domain.report.dto.ReportAiRequests;
import io.skala.backend.domain.report.dto.TemplateResponse;
import io.skala.backend.domain.report.service.ReportAiRelayService;
import io.skala.backend.domain.report.service.ReportExportService;
import io.skala.backend.domain.report.service.ReportTemplateService;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.response.PageResponse;
import io.skala.backend.global.security.AuthenticatedUser;
import java.time.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** DB·모델·디스크 접근 없이 보고서 선택·근거·내보내기 연결을 검증한다. */
class DecisionReportToolsTest {
    private final ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule());
    private final DecisionAssistantJson json=new DecisionAssistantJson(mapper);
    private final ReportTemplateService templates=mock(ReportTemplateService.class);
    private final ReportAiRelayService relay=mock(ReportAiRelayService.class);
    private final ReportExportService exports=mock(ReportExportService.class);
    private final DecisionReportBasisGuard guard=mock(DecisionReportBasisGuard.class);
    private final DecisionReportArtifactStore artifacts=mock(DecisionReportArtifactStore.class);
    private final Clock clock=Clock.fixed(Instant.parse("2026-11-27T00:00:00Z"),ZoneOffset.UTC);
    private final AuthenticatedUser actor=new AuthenticatedUser(7L,UserRole.PLANNER,null);
    private final DecisionReportBasisGuard.SourceBasis basis=new DecisionReportBasisGuard.SourceBasis("master-2026-11","basis");
    private DecisionAssistantGateway.Run run;
    private DecisionReportTools tools;
    private TemplateResponse template;
    private JsonNode filled;

    /** 목적: 저장 틀·실제 API 형태를 대역으로 준비한다. Args: 없음. Returns: 없음.
     * Raises: 없음. Side Effects: 메모리 대역만. */
    @BeforeEach void prepare() {
        var snapshot=new Snapshot("3","master_ver11.01",2026,11,OffsetDateTime.now(clock),List.of("101"),
                List.of(new Organization("1","A","사업부","10","5","5")),"5");
        run=new DecisionAssistantGateway.Run(actor,snapshot,null,clock.instant().plusSeconds(180),"보고서 만들어줘");
        tools=new DecisionReportTools(templates,relay,exports,guard,artifacts,json,clock);
        template=new TemplateResponse("5","경영 보고서",2,2,tree(Map.of("blocks",List.of(
                Map.of("id","b1","kind","metric_table","metrics",List.of("영업이익"),"compare",List.of("계획"))))),
                OffsetDateTime.now(clock),OffsetDateTime.now(clock));
        when(templates.list(actor,null,10)).thenReturn(new PageResponse<>(List.of(template),null));
        when(templates.get(actor,5L,2)).thenReturn(template);
        when(guard.check(any())).thenReturn(basis);
        filled=tree(Map.of("dataStatus","CONFIRMED","source",Map.of("version","master-2026-11","year",2026,"month",11),
                "blocks",List.of(Map.of("id","b1","kind","metric_table","rows",List.of(List.of(Map.of("text","영업이익"),Map.of("text","0.0","won","123.456")))))));
        when(relay.data(eq(actor),any())).thenReturn(filled);
    }

    /** 목적: 목록에서 SQL·정의를 제외하고 선택 버전만 제공한다. Args: 없음. Returns: 없음.
     * Raises: AssertionError. Side Effects: 메모리 대역 조회만. */
    @Test void exposesOnlyBoundedTemplateMetadata() {
        JsonNode data=tree(tools.templates(run,tree(Map.of("limit",10))).data());
        assertThat(data.at("/items/0/templateId").asText()).isEqualTo("5");
        assertThat(data.at("/items/0/version").asInt()).isEqualTo(2);
        assertThat(data.at("/items/0/blocks/0/blockId").asText()).isEqualTo("b1");
        assertThat(data.toString()).doesNotContain("definition","metrics","sql");
        assertThatThrownBy(() -> tools.templates(run,tree(Map.of("cursor","99","limit",10))))
                .isInstanceOf(DecisionAssistantTools.ToolFailure.class);
    }

    /** 목적: 미조회 버전이 실제 API에 전달되지 않게 한다. Args: 없음. Returns: 없음.
     * Raises: AssertionError. Side Effects: 메모리 대역만. */
    @Test void requiresObservedTemplateVersionBeforeFill() {
        assertThatThrownBy(() -> tools.preview(run,tree(Map.of("templateId","5","version",2))))
                .isInstanceOf(DecisionAssistantTools.ToolFailure.class);
        verifyNoInteractions(relay);
    }

    /** 목적: 기존 API를 고정 연월·저장 블록으로 호출하고 원 수치를 보존한다. Args: 없음. Returns: 없음.
     * Raises: AssertionError. Side Effects: 메모리 대역만. */
    @Test void fillsSelectedTemplateAndChecksFreshSourceBeforeAndAfter() {
        observeTemplates();
        JsonNode data=tree(tools.preview(run,tree(Map.of("templateId","5","version",2,"blockId","b1"))).data());
        var captor=ArgumentCaptor.forClass(ReportAiRequests.Data.class);
        verify(relay).data(eq(actor),captor.capture());
        assertThat(captor.getValue().base()).isEqualTo(new ReportAiRequests.Base(2026,11));
        assertThat(captor.getValue().blocks()).hasSize(1);
        assertThat(data.path("blocks")).isEqualTo(filled.path("blocks"));
        assertThat(data.path("saved").asBoolean()).isFalse();
        verify(guard,times(2)).check(run.snapshot); verify(guard).requireSame(basis,basis);
    }

    /** 목적: 잘못된 출처와 데이터 없는 응답을 완료 보고서로 채택하지 않는다. Args: 없음. Returns: 없음.
     * Raises: AssertionError. Side Effects: 대역만. */
    @Test void rejectsWrongSourceAndMissingData() {
        observeTemplates();
        ((ObjectNode)filled.path("source")).put("month",10);
        assertThatThrownBy(() -> tools.preview(run,tree(Map.of("templateId","5","version",2))))
                .isInstanceOf(DecisionAssistantTools.ToolFailure.class);
        ((ObjectNode)filled.path("source")).put("month",11);
        ((ObjectNode)filled).put("dataStatus","NONE");
        assertThatThrownBy(() -> tools.preview(run,tree(Map.of("templateId","5","version",2))))
                .isInstanceOf(DecisionAssistantTools.ToolFailure.class);
    }

    /** 목적: 고정 기준을 우회하는 저장 SQL·다기간·임의 query 블록도 거부한다. Args: 없음. Returns: 없음.
     * Raises: AssertionError. Side Effects: 외부 호출 없음. */
    @Test void rejectsSqlOtherPeriodsAndQueryBlocksBeforeApiCall() {
        observeTemplates();
        ObjectNode block=(ObjectNode)template.definition().path("blocks").get(0);
        for (String key:List.of("sql","metricId")) {
            block.put(key,"1");
            assertThatThrownBy(() -> tools.preview(run,tree(Map.of("templateId","5","version",2))))
                    .isInstanceOf(DecisionAssistantTools.ToolFailure.class);
            block.remove(key);
        }
        block.set("period",tree(Map.of("rule","fixed","year",2026,"month",10)));
        assertThatThrownBy(() -> tools.preview(run,tree(Map.of("templateId","5","version",2))))
                .isInstanceOf(DecisionAssistantTools.ToolFailure.class);
        block.remove("period"); block.put("kind","query");
        assertThatThrownBy(() -> tools.preview(run,tree(Map.of("templateId","5","version",2))))
                .isInstanceOf(DecisionAssistantTools.ToolFailure.class);
        verifyNoInteractions(relay);
    }

    /** 목적: 성공 미리보기의 원문만 기존 XLSX 렌더러에 전달하고 실제 영수증을 반환한다. Args: 없음.
     * Returns: 없음. Raises: AssertionError. Side Effects: 메모리 대역 바이트만. */
    @Test void exportsOnlyObservedPreviewWithoutRefillingOrSaving() {
        observeTemplates();
        JsonNode preview=tree(tools.preview(run,tree(Map.of("templateId","5","version",2))).data());
        observe("obs-2","report_preview",preview);
        byte[] bytes={1,2,3};
        when(exports.exportFilled(eq(actor),any(),eq(preview))).thenReturn(new ReportExportService.Export("경영 보고서.xlsx",bytes));
        when(artifacts.put(eq(actor),anyString(),eq(bytes))).thenReturn(new DecisionReportArtifactStore.Receipt(
                "8912e4ea-9151-4927-ad8f-a614747fcabc","경영 보고서.xlsx",DecisionReportArtifactStore.CONTENT_TYPE,3,clock.instant().plusSeconds(600)));
        JsonNode receipt=tree(tools.export(run,tree(Map.of("previewObservationId","obs-2"))).data());
        assertThat(receipt.path("previewObservationId").asText()).isEqualTo("obs-2");
        assertThat(receipt.path("ready").asBoolean()).isTrue();
        assertThat(receipt.path("saved").asBoolean()).isFalse();
        verify(relay,times(1)).data(eq(actor),any());
        verify(exports,never()).export(any(),any());
    }

    /** 목적: 미리보기 후 원천이 바뀌면 파일 생성 전에 중단한다. Args: 없음. Returns: 없음.
     * Raises: AssertionError. Side Effects: 대역만. */
    @Test void refusesChangedSourceAndUnknownObservationBeforeExport() {
        assertThatThrownBy(() -> tools.export(run,tree(Map.of("previewObservationId","obs-99"))))
                .isInstanceOf(DecisionAssistantTools.ToolFailure.class);
        observeTemplates(); observe("obs-2","report_preview",tree(tools.preview(run,tree(Map.of("templateId","5","version",2))).data()));
        when(guard.check(any())).thenReturn(new DecisionReportBasisGuard.SourceBasis("master-2026-11","changed"));
        assertThatThrownBy(() -> tools.export(run,tree(Map.of("previewObservationId","obs-2"))))
                .isInstanceOf(DecisionAssistantTools.ToolFailure.class);
        verifyNoInteractions(exports,artifacts);
    }

    /** 목적: 실제 목록 성공 관찰을 준비한다. Args: 없음. Returns: 없음.
     * Raises: 없음. Side Effects: 메모리 원장만. */
    private void observeTemplates() { observe("obs-1","report_templates",tree(tools.templates(run,tree(Map.of("limit",10))).data())); }
    /** 목적: 서버 관찰을 메모리에 추가한다. Args: id 관찰 ID, tool 이름, data 자료. Returns: 없음.
     * Raises: 없음. Side Effects: 테스트 원장만. */
    private void observe(String id,String tool,JsonNode data) {
        run.observations.add(new Observation(id,tool,tool,"SUCCESS",null,data,List.of()));
    }
    /** 목적: 대역 값을 JSON으로 변환한다. Args: value 값. Returns: JSON.
     * Raises: IllegalArgumentException. Side Effects: 메모리만. */
    private JsonNode tree(Object value) { return mapper.valueToTree(value); }
}
