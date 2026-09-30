package com.chaekcheck.library.internal;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface EligibilityRuleRepository extends JpaRepository<EligibilityRule, Long> {

	List<EligibilityRule> findByLibraryIdIn(Collection<Long> libraryIds);

}
