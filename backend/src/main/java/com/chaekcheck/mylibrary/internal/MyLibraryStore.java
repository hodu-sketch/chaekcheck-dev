package com.chaekcheck.mylibrary.internal;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.chaekcheck.auth.ConditionalOnAuthEnabled;

/**
 * Reads and writes {@code mylibrary.account_region} and {@code mylibrary.account_library} with plain SQL (no JPA
 * entities in this module). Every query is by {@code account_id}; the caller holds the account lock for writes.
 */
@Repository
@ConditionalOnAuthEnabled
class MyLibraryStore {

	static final String RESIDENCE = "RESIDENCE";

	static final String WORK = "WORK";

	private final JdbcClient jdbc;

	MyLibraryStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Region ids of one kind in the order they were chosen. */
	List<Long> regions(long accountId, String kind) {
		return jdbc.sql("SELECT region_id FROM mylibrary.account_region WHERE account_id = ? AND kind = ? ORDER BY position")
			.params(accountId, kind)
			.query(Long.class)
			.list();
	}

	/** Replaces both kinds; empty lists leave no row (AC-13). */
	void replaceRegions(long accountId, List<Long> residence, List<Long> work) {
		jdbc.sql("DELETE FROM mylibrary.account_region WHERE account_id = ?").param(accountId).update();
		insertRegions(accountId, RESIDENCE, residence);
		insertRegions(accountId, WORK, work);
	}

	private void insertRegions(long accountId, String kind, List<Long> regionIds) {
		for (int position = 0; position < regionIds.size(); position++) {
			jdbc.sql("""
					INSERT INTO mylibrary.account_region (account_id, kind, region_id, position)
					VALUES (?, ?, ?, ?)""").params(accountId, kind, regionIds.get(position), position).update();
		}
	}

	/** Added libraries, disabled ones included, oldest first. */
	List<Long> libraryIds(long accountId) {
		return jdbc.sql("SELECT library_id FROM mylibrary.account_library WHERE account_id = ? ORDER BY added_at, library_id")
			.param(accountId)
			.query(Long.class)
			.list();
	}

	boolean hasLibrary(long accountId, long libraryId) {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM mylibrary.account_library WHERE account_id = ? AND library_id = ?)")
			.params(accountId, libraryId)
			.query(Boolean.class)
			.single();
	}

	int countLibraries(long accountId) {
		return jdbc.sql("SELECT count(*) FROM mylibrary.account_library WHERE account_id = ?")
			.param(accountId)
			.query(Integer.class)
			.single();
	}

	void addLibrary(long accountId, long libraryId) {
		jdbc.sql("""
				INSERT INTO mylibrary.account_library (account_id, library_id) VALUES (?, ?)
				ON CONFLICT DO NOTHING""").params(accountId, libraryId).update();
	}

	/**
	 * Adds the libraries in the given order: {@code added_at} grows by 1 ms per library (one transaction has one
	 * {@code now()}), so the list keeps the order (ADR-0057) [안].
	 */
	void addLibraries(long accountId, List<Long> libraryIds) {
		for (int i = 0; i < libraryIds.size(); i++) {
			jdbc.sql("""
					INSERT INTO mylibrary.account_library (account_id, library_id, added_at)
					VALUES (?, ?, now() + ? * interval '1 millisecond')
					ON CONFLICT DO NOTHING""").params(accountId, libraryIds.get(i), i).update();
		}
	}

	void removeLibrary(long accountId, long libraryId) {
		jdbc.sql("DELETE FROM mylibrary.account_library WHERE account_id = ? AND library_id = ?")
			.params(accountId, libraryId)
			.update();
	}

	/** Every row of the account; running it again changes nothing. */
	void deleteAccount(long accountId) {
		jdbc.sql("DELETE FROM mylibrary.account_region WHERE account_id = ?").param(accountId).update();
		jdbc.sql("DELETE FROM mylibrary.account_library WHERE account_id = ?").param(accountId).update();
	}

}
