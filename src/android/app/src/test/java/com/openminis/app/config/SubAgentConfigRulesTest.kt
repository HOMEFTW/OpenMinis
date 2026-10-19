package com.openminis.app.config

import com.openminis.app.config.collections.SubAgentConfigRules
import com.openminis.app.data.model.SubAgentDefinition
import org.junit.Assert.assertThrows
import org.junit.Test

class SubAgentConfigRulesTest {
    private val builtIn = SubAgentDefinition(id = SubAgentDefinition.BUILT_IN_ID, name = "General", description = "Default", isBuiltIn = true)
    private val custom = SubAgentDefinition(id = "researcher", name = "Researcher", description = "Search", modelGroupId = "research")
    private val roster = listOf(builtIn, custom)

    @Test fun rejectsDuplicateNamesAndDanglingModelGroups() {
        assertThrows(ConfigError.InvalidValue::class.java) {
            SubAgentConfigRules.validate(custom.copy(name = " general "), roster, setOf("research"))
        }
        assertThrows(ConfigError.InvalidValue::class.java) {
            SubAgentConfigRules.validate(custom.copy(modelGroupId = "deleted"), roster, emptySet())
        }
        SubAgentConfigRules.validate(custom.copy(instructions = "New instructions"), roster, setOf("research"))
    }

    @Test fun protectsBuiltInIdentityAndRequiresCompleteOrdering() {
        assertThrows(ConfigError.PermissionDenied::class.java) {
            SubAgentConfigRules.validate(builtIn.copy(name = "Renamed"), roster, setOf("research"))
        }
        assertThrows(ConfigError.InvalidValue::class.java) { SubAgentConfigRules.validateOrder(listOf(custom.id, builtIn.id), roster) }
        assertThrows(ConfigError.InvalidValue::class.java) { SubAgentConfigRules.validateOrder(listOf(builtIn.id, builtIn.id), roster) }
        SubAgentConfigRules.validateOrder(listOf(builtIn.id, custom.id), roster)
    }
}
