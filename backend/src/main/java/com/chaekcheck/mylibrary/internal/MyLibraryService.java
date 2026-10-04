package com.chaekcheck.mylibrary.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.auth.AccountId;
import com.chaekcheck.auth.AccountPresence;
import com.chaekcheck.auth.ConditionalOnAuthEnabled;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.region.RegionDirectory;
import com.chaekcheck.region.RegionNotUsableException;

/**
 * Saves and reads an account's regions and added libraries. Every write is one transaction that first locks the
 * account row ({@link AccountPresence#lockIfExists}, ADR-0051): a deletion that started earlier makes it 401 with no
 * row written, one that starts later waits, and two adds of the same account cannot both pass the 20 check.
 */
@Service
@ConditionalOnAuthEnabled
public class MyLibraryService {

	/** Libraries one account can add by hand, disabled ones included until removed (spec R3) [확정]. */
	public static final int MAX_LIBRARIES = 20;

	/** Region ids per kind, as for the search APIs [확정]. */
	public static final int MAX_REGIONS = 20;

	static final String RESIDENCE_FIELD = "residenceRegionIds";

	static final String WORK_FIELD = "workRegionIds";

	private final MyLibraryStore store;

	private final AccountPresence accounts;

	private final LibraryCatalog libraries;

	private final RegionDirectory regions;

	MyLibraryService(MyLibraryStore store, AccountPresence accounts, LibraryCatalog libraries,
			RegionDirectory regions) {
		this.store = store;
		this.accounts = accounts;
		this.libraries = libraries;
		this.regions = regions;
	}

	/** Saved ids as they are; abolished ones are resolved by the web (spec R6). */
	@Transactional(readOnly = true)
	public SavedRegions regions(AccountId account) {
		return new SavedRegions(store.regions(account.value(), MyLibraryStore.RESIDENCE),
				store.regions(account.value(), MyLibraryStore.WORK));
	}

	/**
	 * Replaces both kinds in the given order. The lists are already checked for format, duplicates and size.
	 * @throws AccountGoneException when the account has been deleted
	 * @throws InvalidRegionsException when an id is not an active SIGUNGU (422)
	 */
	@Transactional
	public SavedRegions saveRegions(AccountId account, List<Long> residence, List<Long> work) {
		lock(account);
		List<InvalidRegion> invalid = new ArrayList<>();
		check(RESIDENCE_FIELD, residence, invalid);
		check(WORK_FIELD, work, invalid);
		if (!invalid.isEmpty()) {
			throw new InvalidRegionsException(invalid);
		}
		store.replaceRegions(account.value(), residence, work);
		return new SavedRegions(List.copyOf(residence), List.copyOf(work));
	}

	@Transactional(readOnly = true)
	public List<Long> libraryIds(AccountId account) {
		return store.libraryIds(account.value());
	}

	/**
	 * Adds one library (C11): lock, enabled check (404), already there is a success even at 20, otherwise 20 is the
	 * limit (409). Libraries that came in through the regions can be added too.
	 */
	@Transactional
	public void addLibrary(AccountId account, long libraryId) {
		lock(account);
		if (libraries.findEnabledTarget(libraryId).isEmpty()) {
			throw new LibraryNotFoundException(libraryId);
		}
		if (store.hasLibrary(account.value(), libraryId)) {
			return;
		}
		if (store.countLibraries(account.value()) >= MAX_LIBRARIES) {
			throw new LibraryLimitException();
		}
		store.addLibrary(account.value(), libraryId);
	}

	/** Removes one library; one that is not there is a success. Locks like every write (ADR-0051). */
	@Transactional
	public void removeLibrary(AccountId account, long libraryId) {
		lock(account);
		store.removeLibrary(account.value(), libraryId);
	}

	private void lock(AccountId account) {
		if (!accounts.lockIfExists(account)) {
			throw new AccountGoneException();
		}
	}

	private void check(String field, List<Long> ids, List<InvalidRegion> invalid) {
		if (ids.isEmpty()) {
			return;
		}
		try {
			regions.expandForMatching(new LinkedHashSet<>(ids));
		}
		catch (RegionNotUsableException ex) {
			ex.reasons()
				.entrySet()
				.stream()
				.sorted(Map.Entry.comparingByKey())
				.forEach(e -> invalid.add(
						new InvalidRegion(field, e.getKey(), e.getValue().name().toLowerCase(Locale.ROOT))));
		}
	}

	public record SavedRegions(List<Long> residenceRegionIds, List<Long> workRegionIds) {
	}

	/** @param code {@code abolished}, {@code not_found} or {@code not_sigungu} */
	public record InvalidRegion(String field, long regionId, String code) {
	}

	/** The account was deleted between the token check and the lock: 401. */
	public static final class AccountGoneException extends RuntimeException {

		AccountGoneException() {
			super("Account not found");
		}

	}

	public static final class LibraryNotFoundException extends RuntimeException {

		LibraryNotFoundException(long libraryId) {
			super("Library not found or disabled: " + libraryId);
		}

	}

	public static final class LibraryLimitException extends RuntimeException {

		LibraryLimitException() {
			super("At most " + MAX_LIBRARIES + " libraries can be added");
		}

	}

	public static final class InvalidRegionsException extends RuntimeException {

		private final List<InvalidRegion> invalidRegions;

		InvalidRegionsException(List<InvalidRegion> invalidRegions) {
			super("Invalid regions: " + invalidRegions);
			this.invalidRegions = List.copyOf(invalidRegions);
		}

		public List<InvalidRegion> invalidRegions() {
			return invalidRegions;
		}

	}

}
