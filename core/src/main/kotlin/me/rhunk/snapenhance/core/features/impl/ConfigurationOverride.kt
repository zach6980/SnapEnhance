package me.rhunk.snapenhance.core.features.impl

import de.robv.android.xposed.XposedHelpers
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.features.FeatureLoadParams
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.Hooker
import me.rhunk.snapenhance.core.util.hook.hook
import me.rhunk.snapenhance.core.util.ktx.getObjectField
import me.rhunk.snapenhance.core.util.ktx.setObjectField

data class ConfigKeyInfo(
    val category: String?,
    val name: String?,
    val defaultValue: Any?
)

class ConfigurationOverride : Feature("Configuration Override", loadParams = FeatureLoadParams.INIT_SYNC) {
    override fun init() {
        val compositeConfigurationProviderMappings = context.mappings.getMappedMap("CompositeConfigurationProvider")
        val enumMappings = compositeConfigurationProviderMappings["enum"] as Map<*, *>

        fun getConfigKeyInfo(key: Any?) = runCatching {
            if (key == null) return@runCatching null
            val keyClassMethods = key::class.java.methods
            val keyName = keyClassMethods.firstOrNull { it.name == "getName" }?.invoke(key)?.toString() ?: key.toString()
            val category = keyClassMethods.firstOrNull { it.name == enumMappings["getCategory"].toString() }?.invoke(key)?.toString() ?: return null
            val valueHolder = keyClassMethods.firstOrNull { it.name == enumMappings["getValue"].toString() }?.invoke(key) ?: return null
            val defaultValue = valueHolder.getObjectField(enumMappings["defaultValueField"].toString()) ?: return null
            ConfigKeyInfo(category, keyName, defaultValue)
        }.onFailure {
            context.log.error("Failed to get config key info", it)
        }.getOrNull()

        val propertyOverrides = mutableMapOf<String, Pair<((ConfigKeyInfo) -> Boolean), Any>>()

        fun overrideProperty(key: String, filter: (ConfigKeyInfo) -> Boolean, value: Any) {
            propertyOverrides[key] = Pair(filter, value)
        }

        overrideProperty("STREAK_EXPIRATION_INFO", { context.config.userInterface.streakExpirationInfo.get() }, true)

        overrideProperty("MEDIA_RECORDER_MAX_QUALITY_LEVEL", { context.config.camera.forceCameraSourceEncoding.get() }, true)
        overrideProperty("REDUCE_MY_PROFILE_UI_COMPLEXITY", { context.config.userInterface.mapFriendNameTags.get() }, true)
        overrideProperty("ENABLE_LONG_SNAP_SENDING", { context.config.global.disableSnapSplitting.get() }, true)

        context.config.userInterface.storyViewerOverride.getNullable()?.let { state ->
            overrideProperty("DF_ENABLE_SHOWS_PAGE_CONTROLS", { state == "DISCOVER_PLAYBACK_SEEKBAR" }, true)
            overrideProperty("DF_VOPERA_FOR_STORIES", { state == "VERTICAL_STORY_VIEWER" }, true)
        }

        overrideProperty("SPOTLIGHT_5TH_TAB_ENABLED", { context.config.userInterface.disableSpotlight.get() }, false)

        overrideProperty("BYPASS_AD_FEATURE_GATE", { context.config.global.blockAds.get() }, true)
        arrayOf("CUSTOM_AD_TRACKER_URL", "CUSTOM_AD_INIT_SERVER_URL", "CUSTOM_AD_SERVER_URL").forEach {
            overrideProperty(it, { context.config.global.blockAds.get() }, "http://127.0.0.1")
        }

        findClass(compositeConfigurationProviderMappings["class"].toString()).hook(
            compositeConfigurationProviderMappings["getProperty"].toString(),
            HookStage.AFTER
        ) { param ->
            val propertyKey = getConfigKeyInfo(param.argNullable<Any>(0)) ?: return@hook

            propertyOverrides[propertyKey.name]?.let { (filter, value) ->
                if (!filter(propertyKey)) return@let
                param.setResult(value)
            }
        }

        findClass(compositeConfigurationProviderMappings["class"].toString()).hook(
            compositeConfigurationProviderMappings["observeProperty"].toString(),
            HookStage.BEFORE
        ) { param ->
            val enumData = param.arg<Any>(0)
            val key = enumData.toString()
            val setValue: (Any?) -> Unit = { value ->
                val valueHolder = XposedHelpers.callMethod(enumData, enumMappings["getValue"].toString())
                valueHolder.setObjectField(enumMappings["defaultValueField"].toString(), value)
            }

            propertyOverrides[key]?.let { (filter, value) ->
                if (!filter(getConfigKeyInfo(enumData) ?: return@let)) return@let
                setValue(value)
            }
        }

        runCatching {
            val appExperimentProviderMappings = compositeConfigurationProviderMappings["appExperimentProvider"] as Map<*, *>
            val customBooleanPropertyRules = mutableListOf<(ConfigKeyInfo) -> Boolean>()

            findClass(appExperimentProviderMappings["GetBooleanAppExperimentClass"].toString()).hook("invoke", HookStage.BEFORE) { param ->
                val keyInfo = getConfigKeyInfo(param.arg(1)) ?: return@hook
                if (keyInfo.defaultValue !is Boolean) return@hook
                if (customBooleanPropertyRules.any { it(keyInfo) }) {
                    param.setResult(true)
                    return@hook
                }
                propertyOverrides[keyInfo.name]?.let { (filter, value) ->
                    if (!filter(keyInfo)) return@let
                    param.setResult(value)
                }
            }

            Hooker.ephemeralHookConstructor(
                findClass(compositeConfigurationProviderMappings["class"].toString()),
                HookStage.AFTER
            ) { constructorParam ->
                val instance = constructorParam.thisObject<Any>()
                val appExperimentProviderInstance = instance::class.java.fields.firstOrNull {
                    findClass(appExperimentProviderMappings["class"].toString()).isAssignableFrom(it.type)
                }?.get(instance) ?: return@ephemeralHookConstructor

                appExperimentProviderInstance::class.java.methods.first {
                    it.name == appExperimentProviderMappings["hasExperimentMethod"].toString()
                }.hook(HookStage.BEFORE) { param ->
                    val keyInfo = getConfigKeyInfo(param.arg(0)) ?: return@hook
                    if (keyInfo.defaultValue !is Boolean) return@hook
                    if (customBooleanPropertyRules.any { it(keyInfo) }) {
                        param.setResult(true)
                        return@hook
                    }

                    val propertyOverride = propertyOverrides[keyInfo.name] ?: return@hook
                    if (propertyOverride.first(keyInfo)) param.setResult(true)
                }
            }

            if (context.config.experimental.hiddenSnapchatPlusFeatures.get()) {
                customBooleanPropertyRules.add { key ->
                    key.category == "PLUS" && key.name?.endsWith("_GATE") == true
                }
            }
        }.onFailure {
            context.log.error("Failed to hook appExperimentProvider", it)
        }
    }
}