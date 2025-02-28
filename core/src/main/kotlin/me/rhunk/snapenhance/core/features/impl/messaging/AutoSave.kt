package me.rhunk.snapenhance.core.features.impl.messaging

import me.rhunk.snapenhance.common.data.MessageState
import me.rhunk.snapenhance.common.data.MessageUpdate
import me.rhunk.snapenhance.common.data.MessagingRuleType
import me.rhunk.snapenhance.core.features.FeatureLoadParams
import me.rhunk.snapenhance.core.features.MessagingRuleFeature
import me.rhunk.snapenhance.core.features.impl.spying.MessageLogger
import me.rhunk.snapenhance.core.features.impl.spying.StealthMode
import me.rhunk.snapenhance.core.logger.CoreLogger
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.Hooker
import me.rhunk.snapenhance.core.util.ktx.getObjectField
import me.rhunk.snapenhance.core.wrapper.impl.Message
import me.rhunk.snapenhance.core.wrapper.impl.SnapUUID
import java.util.concurrent.Executors

class AutoSave : MessagingRuleFeature("Auto Save", MessagingRuleType.AUTO_SAVE, loadParams = FeatureLoadParams.ACTIVITY_CREATE_ASYNC) {
    private val asyncSaveExecutorService = Executors.newSingleThreadExecutor()

    private val messageLogger by lazy { context.feature(MessageLogger::class) }
    private val messaging by lazy { context.feature(Messaging::class) }

    private val autoSaveFilter by lazy {
        context.config.messaging.autoSaveMessagesInConversations.get()
    }

    private fun saveMessage(conversationId: SnapUUID, message: Message) {
        val messageId = message.messageDescriptor!!.messageId!!
        if (messageLogger.takeIf { it.isEnabled }?.isMessageDeleted(conversationId.toString(), messageId) == true) return
        if (message.messageState != MessageState.COMMITTED) return

        runCatching {
            context.feature(Messaging::class).conversationManager?.updateMessage(
                conversationId.toString(),
                messageId,
                MessageUpdate.SAVE
            ) {
                if (it != null) {
                    context.log.warn("Error saving message $messageId: $it")
                }
            }
        }.onFailure {
            context.log.error("Error saving message $messageId", it)
        }

        //delay between saves
        Thread.sleep(100L)
    }

    private fun canSaveMessage(message: Message): Boolean {
        if (context.mainActivity == null || context.isMainActivityPaused) return false
        if (message.messageMetadata!!.savedBy!!.any { uuid -> uuid.toString() == context.database.myUserId }) return false
        val contentType = message.messageContent!!.contentType.toString()

        return autoSaveFilter.any { it == contentType }
    }

    private fun canSaveInConversation(targetConversationId: String): Boolean {
        val messaging = context.feature(Messaging::class)
        val openedConversationId = messaging.openedConversationUUID?.toString() ?: return false

        if (openedConversationId != targetConversationId) return false

        if (context.feature(StealthMode::class).canUseRule(openedConversationId)) return false
        if (!canUseRule(openedConversationId)) return false

        return true
    }

    override fun asyncOnActivityCreate() {
        //called when enter in a conversation (or when a message is sent)
        Hooker.hook(
            context.mappings.getMappedClass("callbacks", "FetchConversationWithMessagesCallback"),
            "onFetchConversationWithMessagesComplete",
            HookStage.BEFORE,
            { autoSaveFilter.isNotEmpty() }
        ) { param ->
            val conversationId = SnapUUID(param.arg<Any>(0).getObjectField("mConversationId")!!)
            if (!canSaveInConversation(conversationId.toString())) return@hook

            val messages = param.arg<List<Any>>(1).map { Message(it) }
            messages.forEach {
                if (!canSaveMessage(it)) return@forEach
                asyncSaveExecutorService.submit {
                    saveMessage(conversationId, it)
                }
            }
        }

        //called when a message is received
        Hooker.hook(
            context.mappings.getMappedClass("callbacks", "FetchMessageCallback"),
            "onFetchMessageComplete",
            HookStage.BEFORE,
            { autoSaveFilter.isNotEmpty() }
        ) { param ->
            val message = Message(param.arg(0))
            val conversationId = message.messageDescriptor!!.conversationId!!
            if (!canSaveInConversation(conversationId.toString())) return@hook
            if (!canSaveMessage(message)) return@hook

            asyncSaveExecutorService.submit {
                saveMessage(conversationId, message)
            }
        }

        Hooker.hook(
            context.mappings.getMappedClass("callbacks", "SendMessageCallback"),
            "onSuccess",
            HookStage.BEFORE,
            { autoSaveFilter.isNotEmpty() }
        ) {
            val conversationUUID = messaging.openedConversationUUID ?: return@hook
            runCatching {
                messaging.conversationManager?.fetchConversationWithMessagesPaginated(
                    conversationUUID.toString(),
                    Long.MAX_VALUE,
                    10,
                    onSuccess = {},
                    onError = {}
                )
            }.onFailure {
                CoreLogger.xposedLog("failed to save message", it)
            }
        }
    }
}