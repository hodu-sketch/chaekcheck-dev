package com.chaekcheck.sitecontent.internal;

import java.time.LocalDate;

/** A version number and the day it takes effect. */
public record VersionDate(int number, LocalDate effectiveOn) {
}
