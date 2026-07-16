package com.seedshiftradio.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.seedshiftradio.common.config.JacksonConfig;
import com.seedshiftradio.common.config.OpenApiConfig;
import com.seedshiftradio.common.security.AdminApiGuard;
import com.seedshiftradio.letter.LetterController;
import com.seedshiftradio.letter.LetterPublicController;
import com.seedshiftradio.letter.LetterPublicService;
import com.seedshiftradio.letter.LetterService;
import com.seedshiftradio.monitor.HealthController;
import com.seedshiftradio.monitor.HealthService;
import com.seedshiftradio.monitor.MonitorController;
import com.seedshiftradio.monitor.MonitorService;
import com.seedshiftradio.programming.ProgramTemplateController;
import com.seedshiftradio.programming.ProgrammingAdminService;
import com.seedshiftradio.radio.PlayHistoryController;
import com.seedshiftradio.radio.PlayHistoryQueryService;
import com.seedshiftradio.radio.RadioController;
import com.seedshiftradio.radio.RadioService;
import com.seedshiftradio.settings.AssetService;
import com.seedshiftradio.settings.SettingsController;
import com.seedshiftradio.settings.SettingsService;
import com.seedshiftradio.station.StationAdminService;
import com.seedshiftradio.station.StationController;
import com.seedshiftradio.stream.StreamController;
import com.seedshiftradio.stream.StreamEventService;

@Tag("api-contract")
@SpringBootTest(
		classes = ApiContractTests.ContractTestApplication.class,
		properties = {
				"springdoc.api-docs.path=/api/openapi",
				"spring.main.banner-mode=off"
		})
@AutoConfigureMockMvc
class ApiContractTests {

	private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete");
	private static final Path API_DOCUMENT = Path.of("doc", "02_API仕様書.md");

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ObjectMapper objectMapper;

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	RequestMappingHandlerMapping handlerMapping;

	@MockitoBean
	AdminApiGuard adminApiGuard;

	@MockitoBean
	AssetService assetService;

	@MockitoBean
	HealthService healthService;

	@MockitoBean
	LetterPublicService letterPublicService;

	@MockitoBean
	LetterService letterService;

	@MockitoBean
	MonitorService monitorService;

	@MockitoBean
	PlayHistoryQueryService playHistoryQueryService;

	@MockitoBean
	ProgrammingAdminService programmingAdminService;

	@MockitoBean
	RadioService radioService;

	@MockitoBean
	SettingsService settingsService;

	@MockitoBean
	StationAdminService stationAdminService;

	@MockitoBean
	StreamEventService streamEventService;

	@Test
	void openApiRuntimeHandlersAndDesignDocumentStayInSync() throws Exception {
		JsonNode openApi = loadOpenApi();
		Map<OperationKey, Access> expected = loadAuthenticationMatrix();

		assertEquals(expected, openApiAccess(openApi), "OpenAPI の endpoint または認証区分が認証マトリクスと一致しません。\n" + updateHint(openApi));
		assertEquals(expected, runtimeAccess(), "Spring MVC handler の endpoint または X-Admin-Token 契約が認証マトリクスと一致しません。");
		assertDocumented(expected);
		assertSnapshot(openApi);
	}

	private JsonNode loadOpenApi() throws Exception {
		String content = mockMvc.perform(get("/api/openapi"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString(StandardCharsets.UTF_8);
		JsonNode openApi = objectMapper.readTree(content);
		JsonNode scheme = openApi.path("components").path("securitySchemes").path(OpenApiConfig.ADMIN_SECURITY_SCHEME);
		assertEquals("apiKey", scheme.path("type").asText());
		assertEquals("header", scheme.path("in").asText());
		assertEquals(AdminApiGuard.HEADER_NAME, scheme.path("name").asText());
		return openApi;
	}

	private Map<OperationKey, Access> loadAuthenticationMatrix() throws IOException {
		JsonNode rows = objectMapper.readTree(new ClassPathResource("contracts/api-auth-matrix.json").getInputStream());
		Map<OperationKey, Access> matrix = new TreeMap<>();
		for (JsonNode row : rows) {
			OperationKey key = new OperationKey(row.path("method").asText(), row.path("path").asText());
			Access previous = matrix.put(key, Access.valueOf(row.path("access").asText()));
			assertEquals(null, previous, "認証マトリクスに重複があります: " + key);
		}
		return matrix;
	}

	private Map<OperationKey, Access> openApiAccess(JsonNode openApi) {
		Map<OperationKey, Access> result = new TreeMap<>();
		openApi.path("paths").fields().forEachRemaining(pathEntry -> pathEntry.getValue().fields().forEachRemaining(operationEntry -> {
			if (!HTTP_METHODS.contains(operationEntry.getKey())) {
				return;
			}
			JsonNode security = operationEntry.getValue().path("security");
			boolean admin = security.isArray() && Arrays.stream(toArray(security))
					.anyMatch(requirement -> requirement.has(OpenApiConfig.ADMIN_SECURITY_SCHEME));
			result.put(
					new OperationKey(operationEntry.getKey().toUpperCase(), pathEntry.getKey()),
					admin ? Access.ADMIN : Access.PUBLIC);
		}));
		return result;
	}

	private Map<OperationKey, Access> runtimeAccess() {
		Map<OperationKey, Access> result = new TreeMap<>();
		for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
			HandlerMethod handler = entry.getValue();
			if (!handler.getBeanType().getPackageName().startsWith("com.seedshiftradio")) {
				continue;
			}
			Set<String> paths = entry.getKey().getPathPatternsCondition().getPatternValues();
			entry.getKey().getMethodsCondition().getMethods().forEach(method -> paths.forEach(path -> result.put(
					new OperationKey(method.name(), path),
					requiresAdminHeader(handler) ? Access.ADMIN : Access.PUBLIC)));
		}
		return result;
	}

	private boolean requiresAdminHeader(HandlerMethod handler) {
		for (MethodParameter parameter : handler.getMethodParameters()) {
			RequestHeader requestHeader = parameter.getParameterAnnotation(RequestHeader.class);
			if (requestHeader == null) {
				continue;
			}
			String name = requestHeader.name().isBlank() ? requestHeader.value() : requestHeader.name();
			if (AdminApiGuard.HEADER_NAME.equals(name)) {
				return true;
			}
		}
		return false;
	}

	private void assertDocumented(Map<OperationKey, Access> expected) throws IOException {
		String document = Files.readString(API_DOCUMENT, StandardCharsets.UTF_8);
		for (Map.Entry<OperationKey, Access> entry : expected.entrySet()) {
			String row = "| `" + entry.getKey().method() + "` | `" + entry.getKey().path() + "` | `" + entry.getValue() + "` |";
			assertTrue(document.contains(row), "doc/02 の認証マトリクスに行がありません: " + row);
		}
	}

	private void assertSnapshot(JsonNode openApi) throws IOException, NoSuchAlgorithmException {
		String expectedHash = new ClassPathResource("contracts/openapi.sha256")
				.getContentAsString(StandardCharsets.UTF_8)
				.trim();
		String apiDocument = Files.readString(API_DOCUMENT, StandardCharsets.UTF_8);
		assertTrue(apiDocument.contains(expectedHash), "doc/02 に現在の OpenAPI snapshot hash が記録されていません。");
		String actualHash = snapshotHash(openApi);
		assertEquals(expectedHash, actualHash, "OpenAPI の endpoint または DTO schema が snapshot から変わりました。\n" + updateHint(openApi));
	}

	private String updateHint(JsonNode openApi) {
		try {
			return "契約変更が意図どおりなら contracts/api-auth-matrix.json、doc/02、openapi.sha256 を同時更新してください。actual sha256="
					+ snapshotHash(openApi);
		} catch (Exception exception) {
			return "OpenAPI snapshot hash の計算に失敗しました: " + exception.getMessage();
		}
	}

	private String snapshotHash(JsonNode openApi) throws IOException, NoSuchAlgorithmException {
		JsonNode canonical = canonicalize(openApi);
		byte[] bytes = objectMapper.writeValueAsBytes(canonical);
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	private JsonNode canonicalize(JsonNode node) {
		if (node.isObject()) {
			ObjectNode result = JsonNodeFactory.instance.objectNode();
			Map<String, JsonNode> sorted = new TreeMap<>();
			node.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), canonicalize(entry.getValue())));
			sorted.forEach(result::set);
			return result;
		}
		if (node.isArray()) {
			ArrayNode result = JsonNodeFactory.instance.arrayNode();
			node.forEach(value -> result.add(canonicalize(value)));
			return result;
		}
		return node.deepCopy();
	}

	private JsonNode[] toArray(JsonNode arrayNode) {
		JsonNode[] values = (JsonNode[]) Array.newInstance(JsonNode.class, arrayNode.size());
		for (int index = 0; index < arrayNode.size(); index++) {
			values[index] = arrayNode.get(index);
		}
		return values;
	}

	private enum Access {
		PUBLIC,
		ADMIN
	}

	private record OperationKey(String method, String path) implements Comparable<OperationKey> {
		@Override
		public int compareTo(OperationKey other) {
			int pathOrder = path.compareTo(other.path);
			return pathOrder != 0 ? pathOrder : method.compareTo(other.method);
		}
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration(excludeName = {
			"org.jobrunr.spring.autoconfigure.JobRunrAutoConfiguration",
			"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
	})
	@Import({
			JacksonConfig.class,
			OpenApiConfig.class,
			HealthController.class,
			LetterController.class,
			LetterPublicController.class,
			MonitorController.class,
			PlayHistoryController.class,
			ProgramTemplateController.class,
			RadioController.class,
			SettingsController.class,
			StationController.class,
			StreamController.class
	})
	static class ContractTestApplication {
	}
}
