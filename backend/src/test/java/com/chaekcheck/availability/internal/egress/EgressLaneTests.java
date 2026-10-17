package com.chaekcheck.availability.internal.egress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The lane list setting and the mode filter (handoff 1-15 #1). */
class EgressLaneTests {

	@Test
	void emptyProxyListIsTheDirectLaneAlone() {
		assertThat(EgressLane.list("", "webshare")).containsExactly(EgressLane.DIRECT);
		assertThat(EgressLane.list(null, null)).containsExactly(EgressLane.DIRECT);
	}

	@Test
	void proxiesBecomeNumberedLanesInOrder() {
		List<EgressLane> lanes = EgressLane.list(" 10.0.0.1:6811, 10.0.0.2:6699 ,, proxy.example.test:8080", "webshare");

		assertThat(lanes).extracting(EgressLane::id).containsExactly("direct", "proxy-01", "proxy-02", "proxy-03");
		assertThat(lanes.get(1).vendor()).isEqualTo("webshare");
		assertThat(lanes.get(1).proxy().getHostString()).isEqualTo("10.0.0.1");
		assertThat(lanes.get(1).proxy().getPort()).isEqualTo(6811);
		assertThat(lanes.get(3).proxy().getHostString()).isEqualTo("proxy.example.test");
		// the address never appears where a lane is printed (logs)
		assertThat(lanes.get(1).toString()).isEqualTo("proxy-01").doesNotContain("10.0.0.1");
	}

	@Test
	void badEntriesStopTheStartWithoutRevealingTheValue() {
		assertThatThrownBy(() -> EgressLane.list("10.0.0.1", "webshare")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("entry 1")
			.satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("10.0.0.1"));
		assertThatThrownBy(() -> EgressLane.list("10.0.0.1:6811,host:notaport", "webshare"))
			.hasMessageContaining("entry 2");
		assertThatThrownBy(() -> EgressLane.list("user:pw@10.0.0.1:6811", "webshare")).hasMessageContaining("entry 1");
		assertThatThrownBy(() -> EgressLane.list("", "direct")).hasMessageContaining("proxy-vendor");
	}

	@Test
	void modeFiltersTheLanesAndRefusesAnEmptyResult() { // from-cycle-20-proxy-only.md
		List<EgressLane> lanes = EgressLane.list("10.0.0.1:1,10.0.0.2:2", "webshare");

		assertThat(EgressMode.ALL.allowed(lanes)).hasSize(3);
		assertThat(EgressMode.PROXY_ONLY.allowed(lanes)).extracting(EgressLane::id).containsExactly("proxy-01",
				"proxy-02");
		assertThat(EgressMode.DIRECT_ONLY.allowed(lanes)).containsExactly(EgressLane.DIRECT);
		assertThatThrownBy(() -> EgressMode.PROXY_ONLY.allowed(List.of(EgressLane.DIRECT)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("PROXY_ONLY");
	}

}
