package me.rhunk.snapenhance.core.action.impl

import android.app.AlertDialog
import android.content.DialogInterface
import android.os.Environment
import android.text.InputType
import android.widget.EditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import me.rhunk.snapenhance.common.data.ContentType
import me.rhunk.snapenhance.common.database.impl.FriendFeedEntry
import me.rhunk.snapenhance.core.action.AbstractAction
import me.rhunk.snapenhance.core.features.impl.messaging.Messaging
import me.rhunk.snapenhance.core.logger.CoreLogger
import me.rhunk.snapenhance.core.messaging.ExportFormat
import me.rhunk.snapenhance.core.messaging.MessageExporter
import me.rhunk.snapenhance.core.ui.ViewAppearanceHelper
import me.rhunk.snapenhance.core.wrapper.impl.Message
import java.io.File
import kotlin.math.absoluteValue

class ExportChatMessages : AbstractAction() {
    private val dialogLogs = mutableListOf<String>()
    private var currentActionDialog: AlertDialog? = null

    private var exportType: ExportFormat? = null
    private var mediaToDownload: List<ContentType>? = null
    private var amountOfMessages: Int? = null

    private fun logDialog(message: String) {
        context.runOnUiThread {
            if (dialogLogs.size > 10) dialogLogs.removeAt(0)
            dialogLogs.add(message)
            context.log.debug("dialog: $message", "ExportChatMessages")
            currentActionDialog!!.setMessage(dialogLogs.joinToString("\n"))
        }
    }

    private fun setStatus(message: String) {
        context.runOnUiThread {
            currentActionDialog!!.setTitle(message)
        }
    }

    private suspend fun askExportType() = suspendCancellableCoroutine { cont ->
        context.runOnUiThread {
            ViewAppearanceHelper.newAlertDialogBuilder(context.mainActivity)
                .setTitle(context.translation["chat_export.select_export_format"])
                .setItems(ExportFormat.entries.map { it.name }.toTypedArray()) { _, which ->
                    cont.resumeWith(Result.success(ExportFormat.entries[which]))
                }
                .setOnCancelListener {
                    cont.resumeWith(Result.success(null))
                }
                .show()
        }
    }

    private suspend fun askAmountOfMessages() = suspendCancellableCoroutine { cont ->
        context.coroutineScope.launch(Dispatchers.Main) {
            val input = EditText(context.mainActivity)
            input.inputType = InputType.TYPE_CLASS_NUMBER
            input.setSingleLine()
            input.maxLines = 1

            ViewAppearanceHelper.newAlertDialogBuilder(context.mainActivity)
                .setTitle(context.translation["chat_export.select_amount_of_messages"])
                .setView(input)
                .setPositiveButton(context.translation["button.ok"]) { _, _ ->
                    cont.resumeWith(Result.success(input.text.takeIf { it.isNotEmpty() }?.toString()?.toIntOrNull()?.absoluteValue))
                }
                .setOnCancelListener {
                    cont.resumeWith(Result.success(null))
                }
                .show()
        }
    }

    private suspend fun askMediaToDownload() = suspendCancellableCoroutine { cont ->
        context.runOnUiThread {
            val mediasToDownload = mutableListOf<ContentType>()
            val contentTypes = arrayOf(
                ContentType.SNAP,
                ContentType.EXTERNAL_MEDIA,
                ContentType.NOTE,
                ContentType.STICKER
            )
            ViewAppearanceHelper.newAlertDialogBuilder(context.mainActivity)
                .setTitle(context.translation["chat_export.select_media_type"])
                .setMultiChoiceItems(contentTypes.map { it.name }.toTypedArray(), BooleanArray(contentTypes.size) { false }) { _, which, isChecked ->
                    val media = contentTypes[which]
                    if (isChecked) {
                        mediasToDownload.add(media)
                    } else if (mediasToDownload.contains(media)) {
                        mediasToDownload.remove(media)
                    }
                }
                .setOnCancelListener {
                    cont.resumeWith(Result.success(null))
                }
                .setPositiveButton(context.translation["button.ok"]) { _, _ ->
                    cont.resumeWith(Result.success(mediasToDownload))
                }
                .show()
        }
    }

    override fun run() {
        context.coroutineScope.launch(Dispatchers.Main) {
            exportType = askExportType() ?: return@launch
            mediaToDownload = if (exportType == ExportFormat.HTML) askMediaToDownload() else null
            amountOfMessages = askAmountOfMessages()

            val friendFeedEntries = context.database.getFeedEntries(500)
            val selectedConversations = mutableListOf<FriendFeedEntry>()

            ViewAppearanceHelper.newAlertDialogBuilder(context.mainActivity)
                .setTitle(context.translation["chat_export.select_conversation"])
                .setMultiChoiceItems(
                    friendFeedEntries.map { it.feedDisplayName ?: it.friendDisplayUsername!!.split("|").firstOrNull() }.toTypedArray(),
                    BooleanArray(friendFeedEntries.size) { false }
                ) { _, which, isChecked ->
                    if (isChecked) {
                        selectedConversations.add(friendFeedEntries[which])
                    } else if (selectedConversations.contains(friendFeedEntries[which])) {
                        selectedConversations.remove(friendFeedEntries[which])
                    }
                }
                .setNegativeButton(context.translation["chat_export.dialog_negative_button"]) { dialog, _ ->
                    dialog.dismiss()
                }
                .setNeutralButton(context.translation["chat_export.dialog_neutral_button"]) { _, _ ->
                    exportChatForConversations(friendFeedEntries)
                }
                .setPositiveButton(context.translation["chat_export.dialog_positive_button"]) { _, _ ->
                    exportChatForConversations(selectedConversations)
                }
                .show()
        }
    }

    private suspend fun fetchMessagesPaginated(conversationId: String, lastMessageId: Long, amount: Int): List<Message> = suspendCancellableCoroutine { continuation ->
        context.feature(Messaging::class).conversationManager?.fetchConversationWithMessagesPaginated(conversationId,
            lastMessageId,
            amount, onSuccess = { messages ->
                continuation.resumeWith(Result.success(messages))
            }, onError = {
                continuation.resumeWith(Result.success(emptyList()))
            }) ?: continuation.resumeWith(Result.success(emptyList()))
    }

    private suspend fun exportFullConversation(friendFeedEntry: FriendFeedEntry) {
        //first fetch the first message
        val conversationId = friendFeedEntry.key!!
        val conversationName = friendFeedEntry.feedDisplayName ?: friendFeedEntry.friendDisplayName!!.split("|").lastOrNull() ?: "unknown"

        logDialog(context.translation.format("chat_export.exporting_message", "conversation" to conversationName))

        val foundMessages = fetchMessagesPaginated(conversationId, Long.MAX_VALUE, amount = 1).toMutableList()
        var lastMessageId = foundMessages.firstOrNull()?.messageDescriptor?.messageId ?: run {
            logDialog(context.translation["chat_export.no_messages_found"])
            return
        }

        while (true) {
            val fetchedMessages = fetchMessagesPaginated(conversationId, lastMessageId, amount = 500)
            if (fetchedMessages.isEmpty()) break

            foundMessages.addAll(fetchedMessages)
            if (amountOfMessages != null && foundMessages.size >= amountOfMessages!!) {
                foundMessages.subList(amountOfMessages!!, foundMessages.size).clear()
                break
            }

            fetchedMessages.firstOrNull()?.let {
                lastMessageId = it.messageDescriptor!!.messageId!!
            }
            setStatus("Exporting (${foundMessages.size} / ${foundMessages.firstOrNull()?.orderKey})")
        }

        val outputFile = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "SnapEnhance/conversation_${conversationName}_${System.currentTimeMillis()}.${exportType!!.extension}"
        ).also { it.parentFile?.mkdirs() }

        logDialog(context.translation["chat_export.writing_output"])

        runCatching {
            MessageExporter(
                context = context,
                friendFeedEntry = friendFeedEntry,
                outputFile = outputFile,
                mediaToDownload = mediaToDownload,
                printLog = ::logDialog
            ).apply { readMessages(foundMessages) }.exportTo(exportType!!)
        }.onFailure {
            logDialog(context.translation.format("chat_export.export_failed","conversation" to it.message.toString()))
            context.log.error("Failed to export conversation $conversationName", it)
            return
        }

        dialogLogs.clear()
        logDialog("\n" + context.translation.format("chat_export.exported_to",
            "path" to outputFile.absolutePath.toString()
        ) + "\n")
    }

    private fun exportChatForConversations(conversations: List<FriendFeedEntry>) {
        dialogLogs.clear()
        val jobs = mutableListOf<Job>()

        currentActionDialog = ViewAppearanceHelper.newAlertDialogBuilder(context.mainActivity)
            .setTitle(context.translation["chat_export.exporting_chats"])
            .setCancelable(false)
            .setMessage("")
            .create()
        
        val conversationSize = context.translation.format("chat_export.processing_chats", "amount" to conversations.size.toString())
        
        logDialog(conversationSize)

        context.coroutineScope.launch {
            conversations.forEach { conversation ->
                launch {
                    runCatching {
                        exportFullConversation(conversation)
                    }.onFailure {
                        logDialog(context.translation.format("chat_export.export_fail", "conversation" to conversation.key.toString()))
                        logDialog(it.stackTraceToString())
                        CoreLogger.xposedLog(it)
                    }
                }.also { jobs.add(it) }
            }
            jobs.joinAll()
            logDialog(context.translation["chat_export.finished"])
        }.also {
            currentActionDialog?.setButton(DialogInterface.BUTTON_POSITIVE, context.translation["chat_export.dialog_negative_button"]) { dialog, _ ->
                it.cancel()
                jobs.forEach { it.cancel() }
                dialog.dismiss()
            }
        }

        currentActionDialog!!.show()
    }
}