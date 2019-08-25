package core.bits

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.util.Base64
import android.widget.EditText
import core.*
import filter.hostnameRegex
import g11n.g11Manager
import g11n.i18n
import gs.property.IWhen
import gs.property.device
import gs.property.repo
import gs.property.version
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.blokada.R
import tunnel.*
import tunnel.Filter
import java.net.URL
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.*

class FiltersStatusVB(
        onTap: (SlotView) -> Unit
) : SlotVB(onTap) {

    private var rules: Int = 0
    private var memory: Int = 0

    private val updatingFilters = {
        view?.apply {
            type = Slot.Type.INFO
            val message = i18n.getString(R.string.panel_ruleset_updating)
            content = Slot.Content(
                    label = message,
                    header = i18n.getString(R.string.panel_ruleset),
                    description = message
            )
            date = Date()
        }
        Unit
    }

    private val refreshRuleset = { it: Pair<Int, Int> ->
        rules = it.first
        refresh()
        Unit
    }

    private val refreshMemory = { it: Int ->
        memory = it
        refresh()
        Unit
    }

    private fun refresh() {
        view?.apply {
            type = Slot.Type.COUNTER
            content = Slot.Content(
                    label = i18n.getString(R.string.panel_ruleset_title, Format.counter(rules)),
                    header = i18n.getString(R.string.panel_ruleset),
                    description = i18n.getString(R.string.panel_ruleset_built,
                            Format.counter(rules), Format.counter(memory, round = true))
            )
            date = Date()
        }
    }

    override fun attach(view: SlotView) {
        view.enableAlternativeBackground()
        core.on(Events.RULESET_BUILT, refreshRuleset)
        core.on(Events.FILTERS_CHANGING, updatingFilters)
        core.on(Events.MEMORY_CAPACITY, refreshMemory)
    }

    override fun detach(view: SlotView) {
        core.cancel(Events.RULESET_BUILT, refreshRuleset)
        core.cancel(Events.FILTERS_CHANGING, updatingFilters)
        core.cancel(Events.MEMORY_CAPACITY, refreshMemory)
    }

}

class DomainForwarderVB(
        private val domain: String,
        private val date: Date,
        private val ktx: AndroidKontext,
        private val alternative: Boolean = false,
        onTap: (SlotView) -> Unit
) : SlotVB(onTap) {

    override fun attach(view: SlotView) {
        view.type = Slot.Type.FORWARD
        view.date = date
        view.content = Slot.Content(
                label = i18n.getString(R.string.panel_domain_forwarded, domain),
                header = i18n.getString(R.string.slot_forwarded_title),
                description = domain,
                detail = Format.date(date),
                info = i18n.getString(R.string.panel_domain_forwarded_desc),
                action1 = Slot.Action(i18n.getString(R.string.slot_action_block)) {
                    val f = Filter(
                            id(domain, whitelist = false),
                            source = FilterSourceDescriptor("single", domain),
                            active = true,
                            whitelist = false
                    )
                    tunnelManager.putFilter(ktx, f)
                    view.fold()
                    GlobalScope.launch { showSnack(R.string.panel_domain_blocked_toast) }
                }
                //action2 = Slot.Action(i18n.getString(R.string.slot_action_facts), view.ACTION_NONE)
        )
        if (alternative) view.enableAlternativeBackground()
    }

}

class DomainBlockedVB(
        private val domain: String,
        private val date: Date,
        private val ktx: AndroidKontext,
        private val alternative: Boolean = false,
        onTap: (SlotView) -> Unit
) : SlotVB(onTap) {

    override fun attach(view: SlotView) {
        view.type = Slot.Type.BLOCK
        view.date = date
        view.content = Slot.Content(
                label = i18n.getString(R.string.panel_domain_blocked, domain),
                header = i18n.getString(R.string.slot_blocked_title),
                description = domain,
                detail = Format.date(date),
                info = i18n.getString(R.string.panel_domain_blocked_desc),
                action1 = Slot.Action(i18n.getString(R.string.slot_action_allow)) {
                    val f = Filter(
                            id(domain, whitelist = true),
                            source = FilterSourceDescriptor("single", domain),
                            active = true,
                            whitelist = true
                    )
                    tunnelManager.putFilter(ktx, f)
                    view.fold()
                    GlobalScope.launch { showSnack(R.string.panel_domain_forwarded_toast) }
                }
                //action2 = Slot.Action(i18n.getString(R.string.slot_action_facts), view.ACTION_NONE)
        )
        if (alternative) view.enableAlternativeBackground()
    }

}

class FilterVB(
        private val filter: Filter,
        private val ktx: AndroidKontext,
        private val ctx: Context = ktx.ctx,
        onTap: (SlotView) -> Unit
) : SlotVB(onTap) {

    override fun attach(view: SlotView) {
        val name = filter.customName ?: i18n.localisedOrNull("filters_${filter.id}_name") ?: filter.customComment
        ?: sourceToName(ctx, filter.source)
        val comment = filter.customComment ?: i18n.localisedOrNull("filters_${filter.id}_comment")

        view.enableAlternativeBackground()
        view.type = Slot.Type.INFO
        view.content = Slot.Content(
                label = name,
                description = comment,
                icon = ctx.getDrawable(R.drawable.ic_hexagon_multiple),
                switched = filter.active,
                detail = filter.source.source,
                action2 = Slot.Action(i18n.getString(R.string.slot_action_remove)) {
                    tunnelManager.removeFilter(ktx, filter)
                },
                action3 = Slot.Action(i18n.getString(R.string.slot_action_author)) {
                    try {
                        Intent(Intent.ACTION_VIEW, Uri.parse(filter.credit))
                    } catch (e: Exception) {
                        null
                    }?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(this)
                    }
                }
        )

        view.onSwitch = { on ->
            tunnelManager.putFilter(ktx, filter.copy(active = on))
        }
    }

}

class DownloadListsVB(
        private val ktx: AndroidKontext,
        private val ctx: Context = ktx.ctx,
        onTap: (SlotView) -> Unit
) : SlotVB(onTap) {

    override fun attach(view: SlotView) {
        view.enableAlternativeBackground()
        view.type = Slot.Type.INFO
        view.content = Slot.Content(
                label = i18n.getString(R.string.tunnel_config_refetch_now_title),
                description = i18n.getString(R.string.tunnel_config_refetch_now_description),
                icon = ctx.getDrawable(R.drawable.ic_download),
                action1 = Slot.Action(i18n.getString(R.string.tunnel_config_refetch_now)) {
                    GlobalScope.launch { showSnack(R.string.tunnel_config_refetch_toast) }
                    tunnelManager.invalidateFilters(ktx)
                }
        )
    }

}

class ConfigHelper {
    companion object {
        private fun ttlToId(ttl: Long) = when (ttl) {
            259200L -> R.string.tunnel_config_refetch_frequency_2
            604800L -> R.string.tunnel_config_refetch_frequency_3
            2419200L -> R.string.tunnel_config_refetch_frequency_4
            else -> R.string.tunnel_config_refetch_frequency_1
        }

        private fun idToTtl(id: Int) = when (id) {
            R.string.tunnel_config_refetch_frequency_2 -> 259200L
            R.string.tunnel_config_refetch_frequency_3 -> 604800L
            R.string.tunnel_config_refetch_frequency_4 -> 2419200L
            else -> 86400L
        }

        private fun stringToId(string: String) = when (string) {
            i18n.getString(R.string.tunnel_config_refetch_frequency_2) -> R.string.tunnel_config_refetch_frequency_2
            i18n.getString(R.string.tunnel_config_refetch_frequency_3) -> R.string.tunnel_config_refetch_frequency_3
            i18n.getString(R.string.tunnel_config_refetch_frequency_4) -> R.string.tunnel_config_refetch_frequency_4
            else -> R.string.tunnel_config_refetch_frequency_1
        }

        private fun idToString(id: Int) = i18n.getString(id)

        fun getFrequencyString(ktx: Kontext) = {
            val config = runBlocking { TunnelConfig().loadFromPersistence() }
            idToString(ttlToId(config.cacheTTL))
        }()

        fun setFrequency(ktx: Kontext, string: String) = {
            val config = runBlocking { TunnelConfig().loadFromPersistence() }
            val new = config.copy(cacheTTL = idToTtl(stringToId(string)))
            runBlocking { new.saveToPersistence() }
        }()
    }
}

class ListDownloadFrequencyVB(
        private val ktx: AndroidKontext,
        private val ctx: Context = ktx.ctx,
        onTap: (SlotView) -> Unit
) : SlotVB(onTap) {

    override fun attach(view: SlotView) {
        view.enableAlternativeBackground()
        view.type = Slot.Type.INFO
        view.content = Slot.Content(
                label = i18n.getString(R.string.tunnel_config_refetch_frequency_title),
                description = i18n.getString(R.string.tunnel_config_refetch_frequency_description),
                icon = ctx.getDrawable(R.drawable.ic_timer),
                values = listOf(
                        i18n.getString(R.string.tunnel_config_refetch_frequency_1),
                        i18n.getString(R.string.tunnel_config_refetch_frequency_2),
                        i18n.getString(R.string.tunnel_config_refetch_frequency_3),
                        i18n.getString(R.string.tunnel_config_refetch_frequency_4)
                ),
                selected = ConfigHelper.getFrequencyString(ktx)
        )
        view.onSelect = { selected ->
            ConfigHelper.setFrequency(ktx, selected)
            tunnelManager.reloadConfig(ktx, device.onWifi())
            view.fold()
        }
    }

}

class DownloadOnWifiVB(
        private val ktx: AndroidKontext,
        private val ctx: Context = ktx.ctx,
        onTap: (SlotView) -> Unit
) : SlotVB(onTap) {

    override fun attach(view: SlotView) {
        view.enableAlternativeBackground()
        view.type = Slot.Type.INFO
        view.content = Slot.Content(
                label = i18n.getString(R.string.tunnel_config_wifi_only_title),
                description = i18n.getString(R.string.tunnel_config_wifi_only_description),
                icon = ctx.getDrawable(R.drawable.ic_wifi),
                switched = runBlocking { TunnelConfig().loadFromPersistence() }.wifiOnly
        )
        view.onSwitch = { switched ->
            val new = runBlocking { TunnelConfig().loadFromPersistence() }.copy(wifiOnly = switched)
            runBlocking { new.saveToPersistence() }
            tunnelManager.reloadConfig(ktx, device.onWifi())
        }
    }

}

class NewFilterVB(
        private val ktx: AndroidKontext,
        private val whitelist: Boolean = false,
        private val ctx: Context = ktx.ctx,
        private val nameResId: Int = R.string.slot_new_filter,
        private val modal: ModalManager = modalManager
) : SlotVB() {

    override fun attach(view: SlotView) {
        view.type = Slot.Type.NEW
        view.content = Slot.Content(i18n.getString(nameResId))
        view.onTap = {
            GlobalScope.launch { modal.openModal() }
            ctx.startActivity(Intent(ctx, StepActivity::class.java).apply {
                putExtra(StepActivity.EXTRA_WHITELIST, whitelist)
            })
        }
    }

}

class EnterDomainVB(
        private val ktx: AndroidKontext,
        private val accepted: (List<FilterSourceDescriptor>) -> Unit = {}
) : SlotVB() {

    private var input = ""
    private var inputValid = false

    private fun validate(input: String) = when {
        validateHostname(input) -> null
        validateSeveralHostnames(input) -> null
        validateURL(input) -> null
        else -> i18n.getString(R.string.slot_enter_domain_error)
    }

    private fun validateHostname(it: String) = hostnameRegex.containsMatchIn(it.trim())
    private fun validateSeveralHostnames(it: String) = it.split(",").map { validateHostname(it) }.all { it }
    private fun validateURL(it: String) = try {
        URL(it); true
    } catch (e: Exception) {
        false
    }

    override fun attach(view: SlotView) {
        view.enableAlternativeBackground()
        view.type = Slot.Type.EDIT
        view.content = Slot.Content(i18n.getString(R.string.slot_enter_domain_title),
                description = i18n.getString(R.string.slot_enter_domain_desc),
                action1 = Slot.Action(i18n.getString(R.string.slot_continue)) {
                    if (inputValid) {
                        view.fold()
                        val sources = when {
                            validateSeveralHostnames(input) -> {
                                input.split(",").map {
                                    FilterSourceDescriptor("single", it.trim())
                                }
                            }
                            validateHostname(input) -> listOf(FilterSourceDescriptor("single", input.trim()))
                            else -> listOf(FilterSourceDescriptor("link", input.trim()))
                        }
                        accepted(sources)
                    }
                },
                action2 = Slot.Action(i18n.getString(R.string.slot_enter_domain_file), view.ACTION_NONE)
        )

        view.onInput = { it ->
            input = it
            val error = validate(it)
            inputValid = error == null
            error
        }

        view.requestFocusOnEdit()
    }

}

class EnterNameVB(
        private val ktx: AndroidKontext,
        private val accepted: (String) -> Unit = {}
) : SlotVB(), Stepable {

    var inputForGeneratingName = ""
    private var input = ""
    private var inputValid = false

    private fun validate(input: String) = when {
        input.isNotBlank() -> null
        else -> i18n.getString(R.string.slot_enter_name_error)
    }

    private fun generateName(input: String) = when {
        input.isBlank() -> i18n.getString(R.string.slot_input_suggestion)
        else -> i18n.getString(R.string.slot_input_suggestion_for, input)
    }

    override fun attach(view: SlotView) {
        view.enableAlternativeBackground()
        view.type = Slot.Type.EDIT
        view.content = Slot.Content(i18n.getString(R.string.slot_enter_name_title),
                description = i18n.getString(R.string.slot_enter_name_desc),
                action1 = Slot.Action(i18n.getString(R.string.slot_continue), {
                    if (inputValid) {
                        view.fold()
                        accepted(input)
                    }
                }),
                action2 = Slot.Action(i18n.getString(R.string.slot_enter_name_generate), {
                    view.input = generateName(inputForGeneratingName)
                })
        )

        view.onInput = { it ->
            input = it
            val error = validate(it)
            inputValid = error == null
            error
        }

        view.requestFocusOnEdit()
    }

}

class HomeAppVB(
        private val app: Filter,
        private val ktx: AndroidKontext,
        private val ctx: Context = ktx.ctx,
        onTap: (SlotView) -> Unit
) : SlotVB(onTap) {

    override fun attach(view: SlotView) {
        view.type = Slot.Type.APP
        view.content = Slot.Content(
                label = i18n.getString(R.string.slot_app_label, sourceToName(ctx, app.source)),
                header = i18n.getString(R.string.slot_whitelist_title),
                description = sourceToName(ctx, app.source),
                info = i18n.getString(R.string.slot_app_desc),
                detail = app.source.source,
                icon = sourceToIcon(ctx, app.source.source),
                action1 = Slot.Action(i18n.getString(R.string.slot_action_unwhitelist)) {
                    tunnelManager.removeFilter(ktx, app)
                }
                //action2 = Slot.Action(i18n.getString(R.string.slot_action_facts), view.ACTION_NONE)
        )
    }

}

class SearchBarVB(
        private val ktx: AndroidKontext,
        private val ctx: Context = ktx.ctx,
        val onSearch: (String) -> Unit,
        private val modal: ModalManager = modalManager
) : SlotVB(onTap = {
    GlobalScope.launch { modal.openModal() }
    ctx.startActivity(Intent(ctx, SearchActivity::class.java))
    SearchActivity.setCallback { s ->
        onSearch(s)
        val label = if (s.isEmpty()) i18n.getString(R.string.search_header)
        else i18n.getString(R.string.search_entered, s)

        it.type = Slot.Type.INFO
        it.content = Slot.Content(
                label = label,
                icon = ctx.getDrawable(R.drawable.ic_search)
        )
    }
}) {

    override fun attach(view: SlotView) {
        view.type = Slot.Type.INFO
        if (view.content == null) {
            view.content = Slot.Content(
                    label = i18n.getString(R.string.search_header),
                    icon = ctx.getDrawable(R.drawable.ic_search)
            )
        } else {
            view.content = Slot.Content(
                    label = view.content!!.label,
                    icon = ctx.getDrawable(R.drawable.ic_search)
            )
        }
    }
}

class EnterSearchVB(
        private val ktx: AndroidKontext,
        private val ctx: Context = ktx.ctx,
        private val onSearch: (String) -> Unit
) : SlotVB(onTap = {}) {
    override fun attach(view: SlotView) {
        view.enableAlternativeBackground()
        view.type = Slot.Type.EDIT
        view.content = Slot.Content(
                label = i18n.getString(R.string.search_title),
                icon = ctx.getDrawable(R.drawable.ic_search),
                description = i18n.getString(R.string.search_description),
                action1 = Slot.Action(i18n.getString(R.string.search_action_confirm)) {
                    onSearch((view.findViewById<EditText>(R.id.unfolded_edit)).text.toString())
                },
                action2 = Slot.Action(ctx.getString(R.string.search_action_clear)) {
                    onSearch("")
                })

        view.requestFocusOnEdit()
    }
}

class AppVB(
        private val app: App,
        private val whitelisted: Boolean,
        private val ktx: AndroidKontext,
        private val ctx: Context = ktx.ctx,
        onTap: (SlotView) -> Unit
) : SlotVB(onTap) {

    private val actionWhitelist = Slot.Action(i18n.getString(R.string.slot_allapp_whitelist)) {
        GlobalScope.async {
            showSnack(R.string.slot_whitelist_updating)
            val filter = Filter(
                    id = tunnelManager.findFilterBySource(app.appId).await()?.id
                            ?: id(app.appId, whitelist = true),
                    source = FilterSourceDescriptor("app", app.appId),
                    active = true,
                    whitelist = true
            )
            tunnelManager.putFilter(ktx, filter)
        }
    }

    private val actionCancel = Slot.Action(i18n.getString(R.string.slot_action_unwhitelist)) {
        GlobalScope.async {
            showSnack(R.string.slot_whitelist_updating)
            val filter = Filter(
                    id = tunnelManager.findFilterBySource(app.appId).await()?.id
                            ?: id(app.appId, whitelist = true),
                    source = FilterSourceDescriptor("app", app.appId),
                    active = false,
                    whitelist = true
            )
            tunnelManager.putFilter(ktx, filter)
        }
    }

    override fun attach(view: SlotView) {
        view.enableAlternativeBackground()
        view.type = Slot.Type.APP
        refresh()
    }

    private fun refresh() {
        view?.apply {
            val c = Slot.Content(
                    label = app.label,
                    header = app.label,
                    info = i18n.getString(R.string.slot_allapp_desc),
                    description = app.appId,
                    values = listOf(
                            i18n.getString(R.string.slot_allapp_whitelisted),
                            i18n.getString(R.string.slot_allapp_normal)
                    ),
                    selected = i18n.getString(if (whitelisted) R.string.slot_allapp_whitelisted else R.string.slot_allapp_normal),
                    action1 = if (whitelisted) actionCancel else actionWhitelist
                    //action2 = Slot.Action(i18n.getString(R.string.slot_action_facts), ACTION_NONE)
            )
            content = c
            setAppIcon(AppIconRequest(ctx, app, c, this))
        }
    }

    private fun setAppIcon(request: AppIconRequest) {
        request.view.tag = request.app
        val obj = handler.obtainMessage()
        obj.obj = request
        handler.sendMessageDelayed(obj, 200)
    }

    companion object {
        data class AppIconRequest(val ctx: Context, val app: App, val content: Slot.Content, val view: SlotView)

        private val handler = Handler {
            val r = it.obj as AppIconRequest
            val app = r.view.tag as App
            if (r.app == app) r.view.content = r.content.copy(icon = sourceToIcon(r.ctx, r.app.appId))
            true
        }
    }
}

class AddDnsVB(private val ktx: AndroidKontext,
               private val modal: ModalManager = modalManager): SlotVB({
    GlobalScope.launch { modal.openModal() }
    ktx.ctx.startActivity(Intent(ktx.ctx, AddDnsActivity::class.java))}){
    override fun attach(view: SlotView) {
        view.content = Slot.Content(ktx.ctx.resources.getString(R.string.dns_custom_add_slot))
        view.type = Slot.Type.NEW
    }
}

class DnsChoiceVB(
        private val item: DnsChoice,
        private val ktx: AndroidKontext,
        private val ctx: Context = ktx.ctx,
        onTap: (SlotView) -> Unit
) : SlotVB(onTap) {

    override fun attach(view: SlotView) {
        view.enableAlternativeBackground()

        val id = if (item.id.startsWith("custom-dns:")) Base64.decode(item.id.removePrefix("custom-dns:"), Base64.NO_WRAP).toString(Charset.defaultCharset()) else item.id
        val name = i18n.localisedOrNull("dns_${id}_name") ?: item.comment ?: id.capitalize()
        val description = item.comment ?: i18n.localisedOrNull("dns_${id}_comment")

        val servers = if (item.servers.isNotEmpty()) item.servers else dnsManager.dnsServers()
        val serversString = printServers(servers)

        view.type = Slot.Type.INFO
        view.content = Slot.Content(
                label = name,
                header = name,
                description = description,
                detail = serversString,
                icon = ctx.getDrawable(R.drawable.ic_server),
                switched = item.active,
                action2 = Slot.Action(i18n.getString(R.string.slot_action_author)) {
                    try {
                        Intent(Intent.ACTION_VIEW, Uri.parse(item.credit))
                    } catch (e: Exception) {
                        null
                    }?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(this)
                    }
                },
                action3 = Slot.Action(i18n.getString(R.string.slot_action_remove)) {
                    onTap(view)
                    Handler {
                        if (item.id == "default") {
                            GlobalScope.launch { showSnack(R.string.menu_dns_remove_default) }
                        } else {
                            if (item.active) {
                                dnsManager.choices().firstOrNull()?.active = true
                                dnsManager.enabled %= false
                            }
                            dnsManager.choices %= dnsManager.choices() - item
                        }
                        true
                    }.sendEmptyMessageDelayed(0, 1000)
                }
        )

        view.onSwitch = { switched ->
            if (!switched) {
                dnsManager.choices().first().active = true
                dnsManager.enabled %= false
            } else {
                dnsManager.choices().filter { it.active }.forEach { it.active = false }
            }
            item.active = switched
            dnsManager.choices %= dnsManager.choices()
            if (item.id == "default") dnsManager.enabled %= false
            else dnsManager.enabled %= true
        }
    }

}

class StartOnBootVB(
        private val ktx: AndroidKontext,
        private val ctx: Context = ktx.ctx,
        onTap: (SlotView) -> Unit
) : SlotVB(onTap) {

    override fun attach(view: SlotView) {
        view.enableAlternativeBackground()
        view.type = Slot.Type.INFO
        view.content = Slot.Content(
                label = i18n.getString(R.string.main_autostart_text),
                description = i18n.getString(R.string.slot_start_on_boot_description),
                icon = ctx.getDrawable(R.drawable.ic_power),
                switched = tunnelState.startOnBoot()
        )
        view.onSwitch = { tunnelState.startOnBoot %= it }
    }

}

class KeepAliveVB(
        private val ktx: AndroidKontext,
        private val ctx: Context = ktx.ctx,
        onTap: (SlotView) -> Unit
) : SlotVB(onTap) {

    override fun attach(view: SlotView) {
        view.enableAlternativeBackground()
        view.type = Slot.Type.INFO
        view.content = Slot.Content(
                label = i18n.getString(R.string.notification_keepalive_text),
                icon = ctx.getDrawable(R.drawable.ic_heart_box),
                description = i18n.getString(R.string.notification_keepalive_description),
                switched = keepAlive.keepAlive()
        )
        view.onSwitch = { keepAlive.keepAlive %= it }
    }

}

class WatchdogVB(
        private val ktx: AndroidKontext,
        private val ctx: Context = ktx.ctx,
        onTap: (SlotView) -> Unit
) : SlotVB(onTap) {

    override fun attach(view: SlotView) {
        view.enableAlternativeBackground()
        view.type = Slot.Type.INFO
        view.content = Slot.Content(
                label = i18n.getString(R.string.tunnel_config_watchdog_title),
                icon = ctx.getDrawable(R.drawable.ic_earth),
                description = i18n.getString(R.string.tunnel_config_watchdog_description),
                switched = device.watchdogOn()
        )
        view.onSwitch = { device.watchdogOn %= it }
    }

}

class PowersaveVB(
        private val ktx: AndroidKontext,
        private val ctx: Context = ktx.ctx,
        onTap: (SlotView) -> Unit
) : SlotVB(onTap) {

    override fun attach(view: SlotView) {
        view.enableAlternativeBackground()
        view.type = Slot.Type.INFO
        view.content = Slot.Content(
                label = i18n.getString(R.string.tunnel_config_powersave_title),
                icon = ctx.getDrawable(R.drawable.ic_power),
                description = i18n.getString(R.string.tunnel_config_powersave_description),
                switched = runBlocking { TunnelConfig().loadFromPersistence() }.powersave
        )
        view.onSwitch = {
            val new = runBlocking { TunnelConfig().loadFromPersistence() }.copy(powersave = it)
            runBlocking { new.saveToPersistence() }
        }
    }

}

class DnsFallbackVB(
        private val ktx: AndroidKontext,
        private val ctx: Context = ktx.ctx,
        onTap: (SlotView) -> Unit
) : SlotVB(onTap) {

    override fun attach(view: SlotView) {
        view.enableAlternativeBackground()
        view.type = Slot.Type.INFO
        view.content = Slot.Content(
                label = i18n.getString(R.string.tunnel_config_fallback_title),
                icon = ctx.getDrawable(R.drawable.ic_server),
                description = i18n.getString(R.string.tunnel_config_fallback_description),
                switched = runBlocking { TunnelConfig().loadFromPersistence() }.dnsFallback
        )
        view.onSwitch = {
            val new = runBlocking { TunnelConfig().loadFromPersistence() }.copy(dnsFallback = it)
            runBlocking { new.saveToPersistence() }
        }
    }

}

class ReportVB(
        private val ktx: AndroidKontext,
        private val ctx: Context = ktx.ctx,
        onTap: (SlotView) -> Unit
) : SlotVB(onTap) {

    override fun attach(view: SlotView) {
        view.enableAlternativeBackground()
        view.type = Slot.Type.INFO
        view.content = Slot.Content(
                label = i18n.getString(R.string.tunnel_config_reports_title),
                icon = ctx.getDrawable(R.drawable.ic_heart_box),
                description = i18n.getString(R.string.tunnel_config_reports_description),
                switched = runBlocking { TunnelConfig().loadFromPersistence() }.report
        )
        view.onSwitch = {
            val new = runBlocking { TunnelConfig().loadFromPersistence() }.copy(report = it)
            runBlocking { new.saveToPersistence() }
        }
    }

}

class NotificationsVB(
        private val ktx: AndroidKontext,
        onTap: (SlotView) -> Unit
) : SlotVB(onTap) {

    override fun attach(view: SlotView) {
        view.enableAlternativeBackground()
        view.type = Slot.Type.INFO
        view.content = Slot.Content(
                label = i18n.getString(R.string.notification_on_text),
                switched = ui.notifications()
        )
        view.onSwitch = { ui.notifications %= it }
    }

}

class BackgroundAnimationVB(
        private val ktx: AndroidKontext,
        onTap: (SlotView) -> Unit
) : SlotVB(onTap) {

    override fun attach(view: SlotView) {
        view.enableAlternativeBackground()
        view.type = Slot.Type.INFO
        view.content = Slot.Content(
                icon = ktx.ctx.getDrawable(R.drawable.ic_wifi),
                label = i18n.getString(R.string.slot_background_animation),
                description = i18n.getString(R.string.slot_background_animation_description),
                switched = ui.showBgAnim()
        )
        view.onSwitch = { ui.showBgAnim %= it }
    }

}

class ResetCounterVB(private val ktx: AndroidKontext,
                     onTap: (SlotView) -> Unit
) : SlotVB(onTap) {
    override fun attach(view: SlotView) {
        view.enableAlternativeBackground()
        view.type = Slot.Type.INFO
        view.content = Slot.Content(
                icon = ktx.ctx.getDrawable(R.drawable.ic_delete),
                label = i18n.getString(R.string.slot_reset_counter_label),
                description = i18n.getString(R.string.slot_reset_counter_description),
                action1 = Slot.Action(i18n.getString(R.string.slot_reset_counter_action)) {
                    tunnelState.tunnelDropCount %= 0
                    tunnelState.tunnelDropStart %= System.currentTimeMillis()
                }
        )
    }

}

class DnsListControlVB(
        private val ktx: AndroidKontext,
        private val ctx: Context = ktx.ctx,
        onTap: (SlotView) -> Unit
) : SlotVB(onTap) {

    override fun attach(view: SlotView) {
        view.enableAlternativeBackground()
        view.type = Slot.Type.INFO
        view.content = Slot.Content(
                label = i18n.getString(R.string.slot_dns_control_title),
                description = i18n.getString(R.string.slot_dns_control_description),
                icon = ctx.getDrawable(R.drawable.ic_reload),
                action1 = Slot.Action(i18n.getString(R.string.slot_action_refresh)) {
                    dnsManager.choices.refresh(force = true)
                    GlobalScope.launch { showSnack(R.string.slot_action_refresh_toast) }
                },
                action2 = Slot.Action(i18n.getString(R.string.slot_action_restore)) {
                    dnsManager.choices %= emptyList()
                    dnsManager.choices.refresh()
                }
        )
    }

}

class FiltersListControlVB(
        private val ktx: AndroidKontext,
        private val ctx: Context = ktx.ctx,
        onTap: (SlotView) -> Unit
) : SlotVB(onTap) {

    override fun attach(view: SlotView) {
        view.enableAlternativeBackground()
        view.type = Slot.Type.INFO
        view.content = Slot.Content(
                label = i18n.getString(R.string.slot_filters_title),
                description = i18n.getString(R.string.slot_filters_description),
                icon = ctx.getDrawable(R.drawable.ic_reload),
                action1 = Slot.Action(i18n.getString(R.string.slot_action_refresh)) {
                    GlobalScope.launch { showSnack(R.string.slot_action_refresh_toast) }
                    val ktx = ctx.ktx("quickActions:refresh")
                    filtersManager.apps.refresh(force = true)
                    tunnelManager.invalidateFilters(ktx)
                    g11Manager.invalidateCache(ktx)
                    g11Manager.sync(ktx)
                },
                action2 = Slot.Action(i18n.getString(R.string.slot_action_restore)) {
                    val ktx = ctx.ktx("quickActions:restore")
                    filtersManager.apps.refresh(force = true)
                    tunnelManager.deleteAllFilters(ktx)
                    g11Manager.invalidateCache(ktx)
                    g11Manager.sync(ktx)
                }
        )
    }

}

class StorageLocationVB(
        private val ktx: AndroidKontext,
        private val ctx: Context = ktx.ctx,
        onTap: (SlotView) -> Unit
) : SlotVB(onTap) {

    private val actionExternal = Slot.Action(i18n.getString(R.string.slot_action_external)) {
        v("set persistence path", getExternalPath())
        setPersistencePath(getExternalPath())

        if (!checkStoragePermissions(ktx)) {
            runBlocking {
                getActivity()?.apply {
                    askStoragePermission(ktx, this)
                }
            }
        }
        view?.apply { attach(this) }
    }

    private val actionInternal = Slot.Action(i18n.getString(R.string.slot_action_internal)) {
        v("resetting persistence path")
        setPersistencePath("")
        view?.apply { attach(this) }
    }

    private val actionImport = Slot.Action(i18n.getString(R.string.slot_action_import)) {
        tunnelManager.reloadConfig(ktx, device.onWifi())
    }

    override fun attach(view: SlotView) {
        view.enableAlternativeBackground()
        view.type = Slot.Type.INFO
        view.content = Slot.Content(
                label = i18n.getString(R.string.slot_export_title),
                description = i18n.getString(R.string.slot_export_description),
                icon = ctx.getDrawable(R.drawable.ic_settings_outline),
                values = listOf(
                        i18n.getString(R.string.slot_action_internal),
                        i18n.getString(R.string.slot_action_external)
                ),
                selected = i18n.getString(if (isCustomPersistencePath()) R.string.slot_action_external
                else R.string.slot_action_internal),
                action1 = if (isCustomPersistencePath()) actionInternal else actionExternal,
                action2 = actionImport
        )
    }

}

fun openInBrowser(ctx: Context, url: URL) {
    val intent = Intent(Intent.ACTION_VIEW)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    intent.setData(Uri.parse(url.toString()))
    ctx.startActivity(intent)
}

class UpdateVB(
        onTap: (SlotView) -> Unit
) : SlotVB(onTap) {

    private var listener: IWhen? = null
    private var clickCounter = 0
    private var next: Int = 0

    override fun attach(view: SlotView) {
        view.enableAlternativeBackground()
        listener = repo.lastRefreshMillis.doOnUiWhenSet().then {
            val current = repo.content()
            view.type = Slot.Type.INFO

            val ctx = runBlocking { getActivity()!! }

            if (isUpdate(ctx, current.newestVersionCode)) {
                view.content = Slot.Content(
                        label = i18n.getString(R.string.update_dash_available),
                        description = i18n.getString(R.string.update_notification_text, current.newestVersionName),
                        action1 = Slot.Action(i18n.getString(R.string.update_button)) {
                            if (clickCounter++ % 2 == 0) {
                                GlobalScope.launch { showSnack(R.string.update_starting) }
                                updateCoordinator.start(repo.content().downloadLinks)
                            } else {
                                val intent = Intent(Intent.ACTION_VIEW)
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                intent.setData(Uri.parse(repo.content().downloadLinks[next].toString()))
                                ctx.startActivity(intent)

                                next = next++ % repo.content().downloadLinks.size
                            }
                        },
                        icon = ctx.getDrawable(R.drawable.ic_new_releases)
                )
                view.date = Date()
            } else {
                view.content = Slot.Content(
                        label = i18n.getString(R.string.slot_update_no_updates),
                        description = i18n.getString(R.string.update_info),
                        action1 = Slot.Action(i18n.getString(R.string.slot_update_action_refresh), {
                            repo.content.refresh(force = true)
                        }),
                        icon = ctx.getDrawable(R.drawable.ic_reload)
                )
                view.date = Date(repo.lastRefreshMillis())
            }
        }
    }

    override fun detach(view: SlotView) {
        repo.lastRefreshMillis.cancel(listener)
    }
}

class AboutVB(
        private val ktx: AndroidKontext,
        private val ctx: Context = ktx.ctx,
        onTap: (SlotView) -> Unit
) : SlotVB(onTap) {

    private val creditsAction = Slot.Action(i18n.getString(R.string.main_credits)) {
        openInBrowser(ctx, pages.credits())
    }

    override fun attach(view: SlotView) {
        view.enableAlternativeBackground()
        view.type = Slot.Type.INFO

        view.content = Slot.Content(
                label = i18n.getString(R.string.slot_about),
                description = "${version.appName} ${version.name}",
                detail = blokadaUserAgent(ctx),
                action2 = creditsAction,
                action3 = Slot.Action(i18n.getString(R.string.update_button_appinfo)) {
                },
                action1 = Slot.Action(i18n.getString(R.string.slot_about_share_log)) {
                }
        )

        Handler {
            view.unfold()
            true
        }.sendEmptyMessageDelayed(0, 100)
    }

    private fun newAppDetailsIntent(packageName: String): Intent {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        intent.data = Uri.parse("package:" + packageName)
        return intent
    }

    private fun askForExternalStoragePermissionsIfNeeded(): Boolean {
        return if (!checkStoragePermissions(ktx)) {
            runBlocking {
                getActivity()?.apply {
                    askStoragePermission(ktx, this)
                }
            }
            false
        } else true
    }
}

private val prettyFormat = SimpleDateFormat("MMMM dd, HH:mm")
fun Date.pretty(ktx: Kontext): String {
    return prettyFormat.format(this)
}

class CleanupVB(
        private val ktx: AndroidKontext,
        private val ctx: Context = ktx.ctx
) : ByteVB() {

    override fun attach(view: ByteView) {
        view.icon(null)
        view.label(R.string.home_cleanup.res())
        view.state(R.string.slot_cleanup_desc.res(), smallcap = false)
        view.onTap {
            GlobalScope.launch { showSnack(R.string.welcome_cleanup_done) }
            val builds = getInstalledBuilds()
            for (b in builds.subList(1, builds.size).reversed()) {
                uninstallPackage(b)
            }
        }
    }

    private fun getInstalledBuilds(): List<String> {
        return welcome.conflictingBuilds().map {
            if (isPackageInstalled(it)) it else null
        }.filterNotNull()
    }

    private fun isPackageInstalled(appId: String): Boolean {
        val intent = ctx.packageManager.getLaunchIntentForPackage(appId) as Intent? ?: return false
        val activities = ctx.packageManager.queryIntentActivities(intent, 0)
        return activities.size > 0
    }

    private fun uninstallPackage(appId: String) {
        try {
            val intent = Intent(Intent.ACTION_DELETE)
            intent.data = Uri.parse("package:" + appId)
            ctx.startActivity(intent)
        } catch (e: Exception) {
            e(e)
        }
    }
}
