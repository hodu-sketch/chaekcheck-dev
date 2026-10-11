package com.chaekcheck.catalog.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the catalog load (plan ADR-0065, cycle 08 handoff step 4), {@code chaekcheck.catalog.load.*}.
 * @param lockTimeout how long the swap waits for the catalog tables' lock in one attempt. Default 2s [확정 시작값];
 * PostgreSQL's own default 0 waits without a limit (https://www.postgresql.org/docs/18/runtime-config-client.html,
 * checked 2026-10-11)
 * @param swapAttempts attempts before the load gives up and keeps the previous tables. Default 5
 * @param swapRetryWait rest between two attempts. Default 10s
 * @param maintenanceWorkMem {@code maintenance_work_mem} of the load's own session (index builds), e.g. {@code 1GB};
 * null keeps the server's value. TODO(확인 필요): the value comes from the step 8 measurement
 * @param maxParallelMaintenanceWorkers {@code max_parallel_maintenance_workers} of the load's session; null keeps the
 * server's value. TODO(확인 필요): measured in step 8
 * @param workMem {@code work_mem} of the load's session (the sorts and joins of the write); null keeps the server's
 * value. TODO(확인 필요): measured in step 8
 */
@ConfigurationProperties("chaekcheck.catalog.load")
record CatalogLoadSettings(Duration lockTimeout, Integer swapAttempts, Duration swapRetryWait, String maintenanceWorkMem,
		Integer maxParallelMaintenanceWorkers, String workMem) {

	CatalogLoadSettings {
		lockTimeout = lockTimeout == null ? Duration.ofSeconds(2) : lockTimeout;
		swapAttempts = swapAttempts == null ? 5 : swapAttempts;
		swapRetryWait = swapRetryWait == null ? Duration.ofSeconds(10) : swapRetryWait;
		if (maintenanceWorkMem != null && !maintenanceWorkMem.matches("[0-9]+(kB|MB|GB)")) {
			throw new IllegalArgumentException("maintenance-work-mem must look like 1GB: " + maintenanceWorkMem);
		}
		if (workMem != null && !workMem.matches("[0-9]+(kB|MB|GB)")) {
			throw new IllegalArgumentException("work-mem must look like 64MB: " + workMem);
		}
	}

}
