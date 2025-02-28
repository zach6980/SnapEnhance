package me.rhunk.snapenhance.common.config.impl

import me.rhunk.snapenhance.common.config.ConfigContainer
import me.rhunk.snapenhance.common.config.ConfigFlag

class Scripting : ConfigContainer() {
    val developerMode = boolean("developer_mode", false) { requireRestart() }
    val moduleFolder = string("module_folder", "modules") { addFlags(ConfigFlag.FOLDER); requireRestart()  }
    val hotReload = boolean("hot_reload", false)
    val disableLogAnonymization = boolean("disable_log_anonymization", false) { requireRestart() }
}