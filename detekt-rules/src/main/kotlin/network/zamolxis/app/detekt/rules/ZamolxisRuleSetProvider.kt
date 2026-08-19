package network.zamolxis.app.detekt.rules

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

/**
 * Provides Zamolxis-specific detekt rules.
 */
class ZamolxisRuleSetProvider : RuleSetProvider {
    override val ruleSetId: String = "zamolxis"

    override fun instance(config: Config): RuleSet =
        RuleSet(
            ruleSetId,
            listOf(
                BleLoggingTagRule(config),
                DiscardedConcurrencyReturnRule(config),
                NoCallCoordinatorGetInstanceOutsideHostRule(config),
                NoRnsFacadeInPythonBackend(config),
                ReflectivelyKeptRequiredRule(config),
                NoRelaxedMocksRule(config),
                NoVerifyOnlyTestsRule(config),
                StateFlowPollingLoopRule(config),
            ),
        )
}
