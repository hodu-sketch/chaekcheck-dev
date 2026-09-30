package com.chaekcheck.library.internal;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface LibraryRepository extends JpaRepository<Library, Long> {

	List<Library> findByEnabledTrueOrderById();

}
