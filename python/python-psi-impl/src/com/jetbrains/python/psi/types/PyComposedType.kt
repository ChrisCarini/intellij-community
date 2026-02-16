package com.jetbrains.python.psi.types

import org.jetbrains.annotations.ApiStatus

// TODO Make it sealed once everything is converted to Kotlin
/**
 * A composed type consists of several alternatives.
 * There are three known kinds of such types: unions, "unsafe" unions and intersections.
 *
 * @see PyUnionType
 * @see PyIntersectionType
 * @see PyUnsafeUnionType
 */
@ApiStatus.Experimental
interface PyComposedType : PyType {
  val members: Collection<PyType?>
}