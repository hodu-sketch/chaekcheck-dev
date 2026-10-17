package com.chaekcheck.availability.internal.status;

/**
 * Read-only view of the egress lanes for the status signal (cycle 20, spec R1 additions 1 and 2, review #28):
 * whether every vendor (Webshare) lane is paused, and which lanes the service may use (ADR-0130). Cycle 17/22
 * bring the lane code and will implement this port; until then {@link #NONE} says "nothing paused, every lane",
 * so the answer's {@code reason} is {@code NONE} and only the base rule applies. Defined here first by cycle 20
 * (reported in docs/plan-feedback.md).
 */
public interface EgressLaneStatus {

	/** No lane code yet: nothing paused, every lane allowed. */
	EgressLaneStatus NONE = new EgressLaneStatus() {

		@Override
		public Mode egressMode() {
			return Mode.ALL;
		}

		@Override
		public boolean vendorLanesPaused() {
			return false;
		}

	};

	/** The egress setting of ADR-0130: every lane, proxies only, or the direct lane only. */
	enum Mode {

		ALL, PROXY_ONLY, DIRECT_ONLY

	}

	Mode egressMode();

	/** Every vendor lane is paused (vendor errors 407 / 429, account trouble, or 90% of the data used). */
	boolean vendorLanesPaused();

	/** Lanes paused right now, for the operations summary line; 0 until the lane code exists. */
	default int pausedLaneCount() {
		return 0;
	}

}
