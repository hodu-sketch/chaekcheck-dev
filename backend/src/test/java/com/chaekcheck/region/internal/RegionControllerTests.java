package com.chaekcheck.region.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.chaekcheck.region.RegionResolution;
import com.chaekcheck.region.RegionResolution.Status;

@WebMvcTest(RegionController.class)
class RegionControllerTests {

	@Autowired
	MockMvcTester mvc;

	@MockitoBean
	RegionService regionService;

	@Test
	void returnsTreeWithoutLegalDongCode() {
		given(regionService.activeTree()).willReturn(List.of(new RegionService.SidoNode(11, "서울특별시", "서울",
				List.of(new RegionService.SigunguNode(1114, "마포구")))));

		assertThat(mvc.get().uri("/api/v1/regions")).hasStatusOk()
			.bodyJson()
			.isLenientlyEqualTo("""
					[{"id":11,"name":"서울특별시","shortName":"서울","children":[{"id":1114,"name":"마포구"}]}]
					""");
		assertThat(mvc.get().uri("/api/v1/regions")).bodyText().doesNotContain("legalDongCode");
	}

	@Test
	void resolvesCommaSeparatedIds() {
		given(regionService.resolve(Set.of(1116L, 12345L))).willReturn(List.of(
				new RegionResolution(1116, Status.ACTIVE, "서울 강서구", List.of()),
				new RegionResolution(12345, Status.UNKNOWN, null, List.of())));

		assertThat(mvc.get().uri("/api/v1/regions/resolutions?ids=1116,12345")).hasStatusOk()
			.bodyJson()
			.isStrictlyEqualTo("""
					[{"id":1116,"status":"ACTIVE","label":"서울 강서구","replacements":[]},
					 {"id":12345,"status":"UNKNOWN","label":null,"replacements":[]}]
					""");
	}

	@Test
	void acceptsTwentyIds() {
		given(regionService.resolve(any())).willReturn(List.of());
		assertThat(mvc.get().uri("/api/v1/regions/resolutions?ids=" + ids(20))).hasStatusOk();
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"ids=               | required",
			"                   | required",
			"ids=1,abc          | invalid_format",
			"ids=1,,2           | invalid_format",
			"ids=-3             | invalid_format",
			"ids=99999999999999999999 | invalid_format" })
	void rejectsBadIdsAsProblemDetail(String query, String fieldCode) {
		assertValidationError(query == null ? "" : query, fieldCode);
	}

	@Test
	void rejectsMoreThanTwentyIds() {
		assertValidationError("ids=" + ids(21), "too_many");
	}

	private void assertValidationError(String query, String fieldCode) {
		assertThat(mvc.get().uri("/api/v1/regions/resolutions?" + query)).hasStatus(HttpStatus.BAD_REQUEST)
			.hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
			.bodyJson()
			// "type" is omitted when it is about:blank (Spring Framework 7; RFC 9457 treats a missing type as about:blank)
			.isLenientlyEqualTo("""
					{"title":"Bad Request","status":400,
					 "instance":"/api/v1/regions/resolutions","code":"validation_error",
					 "errors":[{"field":"ids","code":"%s"}]}
					""".formatted(fieldCode));
		verifyNoInteractions(regionService);
	}

	private static String ids(int count) {
		return LongStream.rangeClosed(1, count).mapToObj(Long::toString).collect(Collectors.joining(","));
	}

}
