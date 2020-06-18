package moe.kabii.discord.tasks

import discord4j.core.GatewayDiscordClient
import discord4j.core.`object`.entity.channel.MessageChannel
import discord4j.core.`object`.entity.channel.PrivateChannel
import discord4j.core.`object`.entity.channel.TextChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.time.delay
import moe.kabii.LOG
import moe.kabii.data.relational.Reminder
import moe.kabii.data.relational.Reminders
import moe.kabii.discord.command.reminderColor
import moe.kabii.structure.*
import moe.kabii.util.DurationFormatter
import moe.kabii.util.EmojiCharacters
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import java.time.Duration
import java.time.Instant
import kotlin.math.max

class ReminderWatcher(val discord: GatewayDiscordClient) : Runnable {
    private var active = false
    private val updateInterval = 60_000L

    fun launch() {
        check(!active) { "ReminderWatcher thread already launched" }
        val thread = Thread(this, "ReminderWatcher")
        thread.start()
        active = true
    }

    override fun run() {
        loop {
            // grab reminders ending in next 2 minutes
            val start = Instant.now()
            transaction {
                try {
                    val window = DateTime.now().plus(updateInterval)
                    val reminders = Reminder.find { Reminders.remind lessEq window }.toList()

                    // launch coroutine for precise reminder notifications. run on reminder dispatcher threads
                    val job = SupervisorJob()
                    val discordScope = CoroutineScope(DiscordTaskPool.reminderThreads + job)

                    runBlocking {
                        reminders.map { reminder ->
                            discordScope.launch {
                                scheduleReminder(reminder)
                            }
                        }.joinAll() // wait for all reminders to finish to make sure these are removed before next set
                    }
                } catch(t: Throwable) {
                    LOG.error("Uncaught exception in ReminderWatcher :: ${t.message}")
                    LOG.debug(t.stackTraceString)
                } // don't let this thread die
            }
            val runtime = Duration.between(start, Instant.now())
            val delay = updateInterval - runtime.toMillis()
            Thread.sleep(max(delay, 0L)) // don't sleep negative - not sure how this was happening though
        }
    }

    @WithinExposedContext
    private suspend fun scheduleReminder(reminder: Reminder) {
        // todo check if was old reminder
        val time = Duration.between(Instant.now(), reminder.remind.javaInstant)
        delay(time)
        val user = discord.getUserById(reminder.user.userID.snowflake)
            .tryAwait().orNull()
        if(user == null) {
            LOG.warn("Skipping reminder: user ${reminder.user} not found") // this should not happen
            return
        }
        // get guild channel/pm channel
        val discordChannel = discord.getChannelById(reminder.channel.snowflake)
            .ofType(MessageChannel::class.java)
            .tryAwait().orNull()
        val remindChannel: MessageChannel? = when(discordChannel) {
            is PrivateChannel -> discordChannel
            is TextChannel -> {
                val member = user.asMember(discordChannel.guildId).tryAwait().orNull()
                if(member != null) discordChannel
                else user.privateChannel.tryAwait().orNull() // if user is no longer in guild, send reminder in pm
            }
            else -> null // guild channel can be deleted entirely
        }
        if(remindChannel == null) {
            LOG.info("Skipping reminder: channel ${reminder.channel} is no longer valid")
            return
        }
        val age = Duration.between(reminder.created.javaInstant, Instant.now())
        val createdTime = DurationFormatter(age).fullTime
        val embed: EmbedBlock = {
            reminderColor(this)
            val clock = EmojiCharacters.alarm
            setAuthor("$clock Reminder for ${user.username}#${user.discriminator} $clock", null, user.avatarUrl)
            val created = "Reminder created $createdTime ago."
            val desc = if(reminder.originMessage != null) {
                "[$created](${reminder.originMessage!!.jumpLink})"
            } else created
            setDescription(desc)
            val content = reminder.content
            if(content.isNotBlank()) {
                addField("Reminder:", content, false)
            }
            setFooter("Reminder created", null)
            setTimestamp(reminder.created.javaInstant)
        }
        remindChannel.createMessage { spec ->
            spec.setContent(user.mention)
            spec.setEmbed(embed)
            }.tryAwait() // todo 404 or 403 -> bot removed from channel, resend in PM?
        reminder.delete()
    }
}