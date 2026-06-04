package com.owlmetry.android

/**
 * A single condition that must hold for an auto-triggered questionnaire to
 * present. Conditions read from the persistent [OwlQuestionnaireState] (launch /
 * foreground counts) and a wall-clock `now`. The Kotlin analog of Swift's
 * `OwlQuestionnaireCondition` enum — modeled as a sealed class so [isSatisfied]
 * is exhaustive.
 */
public sealed class OwlQuestionnaireCondition {
    /** Number of times `Owl.configure(...)` has completed (one bump per process). */
    public data class Launches(public val atLeast: Int) : OwlQuestionnaireCondition()

    /** Number of foreground transitions since install. */
    public data class Foregrounds(public val atLeast: Int) : OwlQuestionnaireCondition()

    /** Days since the very first `Owl.configure(...)` call. */
    public data class DaysSinceFirstLaunch(public val atLeast: Int) : OwlQuestionnaireCondition()

    /** Hours since the very first `Owl.configure(...)` call. */
    public data class HoursSinceFirstLaunch(public val atLeast: Int) : OwlQuestionnaireCondition()

    /** Pure evaluator used by the trigger gate and unit tests. */
    public fun isSatisfied(state: OwlQuestionnaireState.Snapshot): Boolean = when (this) {
        is Launches -> state.launchCount >= atLeast
        is Foregrounds -> state.foregroundCount >= atLeast
        is DaysSinceFirstLaunch -> state.daysSinceFirstLaunch() >= atLeast.toDouble()
        is HoursSinceFirstLaunch -> state.hoursSinceFirstLaunch() >= atLeast.toDouble()
    }
}

/**
 * A composable trigger for the Compose `owlQuestionnaire(...)` modifier. All
 * [conditions] are ANDed — for OR logic, use the `isEligible` predicate or split
 * into two modifier applications. [isManual] opts out of auto-trigger entirely.
 * The Kotlin analog of Swift's `OwlQuestionnaireTrigger`.
 */
public data class OwlQuestionnaireTrigger(
    public val conditions: List<OwlQuestionnaireCondition>,
    public val isManual: Boolean,
) {
    /**
     * Evaluate against a state snapshot. Returns true only when every condition
     * is satisfied; [manual] always returns false (handled separately by the
     * gate). Mirrors Swift's `isSatisfied(state:)`.
     */
    public fun isSatisfied(state: OwlQuestionnaireState.Snapshot): Boolean {
        if (isManual) return false
        return conditions.all { it.isSatisfied(state) }
    }

    public companion object {
        /**
         * Never auto-trigger. The consumer drives presentation directly via
         * `OwlQuestionnaireView` or by binding to a state flag.
         */
        public val manual: OwlQuestionnaireTrigger =
            OwlQuestionnaireTrigger(conditions = emptyList(), isManual = true)

        /** Shortcut for `Launches(atLeast = 1)` — fire on first launch. */
        public val afterLaunch: OwlQuestionnaireTrigger =
            OwlQuestionnaireTrigger(
                conditions = listOf(OwlQuestionnaireCondition.Launches(atLeast = 1)),
                isManual = false,
            )

        /** Shortcut for `Launches(atLeast = n)`. */
        public fun afterLaunches(n: Int): OwlQuestionnaireTrigger =
            OwlQuestionnaireTrigger(
                conditions = listOf(OwlQuestionnaireCondition.Launches(atLeast = n)),
                isManual = false,
            )

        /**
         * Composable form — ALL conditions must evaluate true (ANDed). An empty
         * argument list means "always", which combined with `isEligible` is the
         * hook for fully custom gating. Mirrors Swift's variadic `when(_:)`.
         */
        public fun whenAll(vararg conditions: OwlQuestionnaireCondition): OwlQuestionnaireTrigger =
            OwlQuestionnaireTrigger(conditions = conditions.toList(), isManual = false)
    }
}
