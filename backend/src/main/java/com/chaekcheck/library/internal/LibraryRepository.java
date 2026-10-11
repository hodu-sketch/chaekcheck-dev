package com.chaekcheck.library.internal;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.chaekcheck.library.JoinSurvey;

interface LibraryRepository extends JpaRepository<Library, Long> {

	List<Library> findByEnabledTrueOrderById();

	/**
	 * Enabled libraries that can be in a plan (plan ADR-0066): the researched ones, and the ones the user added
	 * whatever their research state (ADR-0053). About 385 rows instead of every enabled library.
	 * @param planned the research states planned without being added
	 * @param chosen the libraries the user added; may be empty
	 */
	@Query("""
			SELECT l FROM Library l
			WHERE l.enabled = true AND (l.joinSurvey IN :planned OR l.id IN :chosen)
			ORDER BY l.id""")
	List<Library> findPlannable(Collection<JoinSurvey> planned, Collection<Long> chosen);

}
