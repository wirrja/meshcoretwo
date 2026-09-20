// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android

import androidx.compose.ui.unit.Dp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateContentSize
import com.meshcoretwo.android.ui.components.accentBackdrop
import android.content.SharedPreferences
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.meshcoretwo.android.about.AboutScreen
import com.meshcoretwo.android.about.LicenseTextScreen
import com.meshcoretwo.android.about.LicensesScreen
import com.meshcoretwo.android.appearance.AppearanceScreen
import com.meshcoretwo.android.settings.DangerZoneScreen
import com.meshcoretwo.android.chat.ChatConversationScreen
import com.meshcoretwo.android.chat.ChatsListScreen
import com.meshcoretwo.android.chat.Conversation
import com.meshcoretwo.android.chat.ConversationTarget
import com.meshcoretwo.android.chat.MessagePathMapScreen
import com.meshcoretwo.android.chat.RoomConversationScreen
import com.meshcoretwo.android.channels.AddChannelScreen
import com.meshcoretwo.android.channels.ChannelInfoScreen
import com.meshcoretwo.android.contacts.AddContactScreen
import com.meshcoretwo.android.contacts.BlockedContactsScreen
import com.meshcoretwo.android.contacts.ContactDetailScreen
import com.meshcoretwo.android.contacts.ContactQRShareScreen
import com.meshcoretwo.android.contacts.ContactLocationHistoryMapScreen
import com.meshcoretwo.android.contacts.ContactsListScreen
import com.meshcoretwo.android.contacts.DiscoveryScreen
import com.meshcoretwo.android.contacts.NeighborSnrChartScreen
import com.meshcoretwo.android.contacts.NeighborSnrMapScreen
import com.meshcoretwo.android.contacts.NodeAuthScreen
import com.meshcoretwo.android.contacts.NodeCLIScreen
import com.meshcoretwo.android.contacts.NodeLocationHistoryMapScreen
import com.meshcoretwo.android.contacts.NodeLocationMapScreen
import com.meshcoretwo.android.contacts.NodeStatusHistoryScreen
import com.meshcoretwo.android.contacts.RepeaterSettingsScreen
import com.meshcoretwo.android.contacts.RepeaterStatusScreen
import com.meshcoretwo.android.contacts.RoomSettingsScreen
import com.meshcoretwo.android.contacts.RoomStatusScreen
import com.meshcoretwo.android.contacts.TelemetryHistoryOverviewScreen
import com.meshcoretwo.android.contacts.TelemetryHistoryScreen
import com.meshcoretwo.android.contacts.neighborPrefixHex
import com.meshcoretwo.android.map.MapScreen
import com.meshcoretwo.android.map.OfflineMapService
import com.meshcoretwo.android.settings.BackupRestoreScreen
import com.meshcoretwo.android.settings.BlockedChannelSendersScreen
import com.meshcoretwo.android.settings.DeviceSelectionScreen
import com.meshcoretwo.android.settings.LocationPickerScreen
import com.meshcoretwo.android.settings.NodeConfigExportScreen
import com.meshcoretwo.android.settings.NodeConfigImportScreen
import com.meshcoretwo.android.settings.OfflineMapSettingsScreen
import com.meshcoretwo.android.settings.OfflineRegionPickerScreen
import com.meshcoretwo.android.settings.RegionManagementScreen
import com.meshcoretwo.android.settings.SettingsScreen
import com.meshcoretwo.protocol.ContactType
import com.meshcoretwo.protocol.hexString
import com.meshcoretwo.android.tools.LineOfSightScreen
import com.meshcoretwo.android.tools.RxLogScreen
import com.meshcoretwo.android.tools.ToolsScreen
import com.meshcoretwo.android.tools.NodeDiscoveryScreen
import com.meshcoretwo.android.tools.TracePathListScreen
import com.meshcoretwo.android.ui.components.LocalOpenDeviceSelection
import com.meshcoretwo.android.ui.theme.ThemeService
import com.meshcoretwo.services.backup.AppBackupService
import com.meshcoretwo.services.connection.ConnectionManager
import com.meshcoretwo.services.connection.connectedDeviceRecord
import com.meshcoretwo.services.location.LocationProvider
import com.meshcoretwo.services.region.RegionResolver
import com.meshcoretwo.services.region.RegionSelectionStore
import com.meshcoretwo.services.notifications.NotificationPreferencesStore
import com.meshcoretwo.services.settings.DevicePreferenceStore
import com.meshcoretwo.services.settings.StaleNodeCleanupPreferencesStore
import com.meshcoretwo.services.persistence.NodeLocationFix
import java.util.UUID
import kotlinx.coroutines.flow.StateFlow

private val TabRoutes = setOf(MainRoute.CHATS, MainRoute.CONTACTS, MainRoute.MAP, MainRoute.TOOLS, MainRoute.SETTINGS)
private const val ScreenTransitionMillis = 260

private fun isTabSwitch(from: String?, to: String?) = from in TabRoutes && to in TabRoutes

/** Tab-to-tab: a quick crossfade. Drill-down/back: a short slide (a fifth of the width) plus fade —
 * lighter than a full-width push and keeps the outgoing screen visible behind it. */
private fun screenEnter(from: String?, to: String?, forward: Boolean): EnterTransition =
    if (isTabSwitch(from, to)) {
        fadeIn(tween(200))
    } else {
        fadeIn(tween(ScreenTransitionMillis)) +
            slideInHorizontally(tween(ScreenTransitionMillis)) { full -> if (forward) full / 5 else -full / 5 }
    }

private fun screenExit(from: String?, to: String?, forward: Boolean): ExitTransition =
    if (isTabSwitch(from, to)) {
        fadeOut(tween(120))
    } else {
        fadeOut(tween(ScreenTransitionMillis)) +
            slideOutHorizontally(tween(ScreenTransitionMillis)) { full -> if (forward) -full / 5 else full / 5 }
    }

internal object MainRoute {
    const val CHATS = "chats"
    const val CONTACTS = "contacts"
    const val MAP = "map"
    const val TOOLS = "tools"
    const val SETTINGS = "settings"
    const val DIRECT_CONVERSATION = "conversation/direct/{contactId}"
    const val CHANNEL_CONVERSATION = "conversation/channel/{index}"
    const val ROOM_CONVERSATION = "conversation/room/{sessionId}"
    const val MESSAGE_PATH_MAP = "chat/message/{messageId}/path-map"
    const val CONTACT_DETAIL = "contact/{contactId}"
    const val NODE_AUTH = "node/{contactId}/auth"
    const val ROOM_STATUS = "room/{sessionId}/status"
    const val REPEATER_STATUS = "repeater/{sessionId}/status"
    const val REPEATER_NEIGHBORS_MAP = "repeater/{sessionId}/neighbors-map"
    const val NODE_STATUS_HISTORY = "node/{sessionId}/history"
    const val NODE_TELEMETRY_HISTORY = "node/{sessionId}/telemetry-history"
    const val CONTACT_TELEMETRY_HISTORY = "contact/{contactId}/telemetry-history"
    const val NODE_LOCATION_MAP = "node/location-map?lat={lat}&lon={lon}&name={name}"
    const val NODE_LOCATION_HISTORY_MAP = "node/{sessionId}/location-history-map?initialSelectionId={initialSelectionId}"
    const val CONTACT_LOCATION_HISTORY_MAP = "contact/{contactId}/location-history-map?initialSelectionId={initialSelectionId}"
    const val REPEATER_NEIGHBOR_CHART = "repeater/{sessionId}/neighbor-chart/{prefix}?name={name}"
    const val NODE_CLI = "node/{sessionId}/cli?isRoom={isRoom}"
    const val REPEATER_SETTINGS = "repeater/{sessionId}/settings"
    const val ROOM_SETTINGS = "room/{sessionId}/settings"
    const val ADD_CHANNEL = "addChannel"
    const val ADD_CHANNEL_PATTERN = "$ADD_CHANNEL?link={link}&hashtag={hashtag}"
    const val CHANNEL_INFO = "channel/{index}/info"
    const val RX_LOG = "tools/rxlog"
    const val LINE_OF_SIGHT = "tools/los"
    const val TRACE_PATH = "tools/tracepath"
    const val NODE_DISCOVERY = "tools/nodediscovery"
    const val DISCOVERY = "contacts/discovery"
    const val BLOCKED_CONTACTS = "contacts/blocked"
    const val ADD_CONTACT = "contacts/add"
    const val ADD_CONTACT_PATTERN = "$ADD_CONTACT?link={link}"
    const val CONTACT_QR_SHARE = "contacts/qr-share?name={name}&publicKeyHex={publicKeyHex}&type={type}"
    const val CONFIG_EXPORT = "settings/config-export"
    const val CONFIG_IMPORT = "settings/config-import"
    const val BACKUP_RESTORE = "settings/backup-restore"
    const val DEVICE_SELECTION = "settings/devices"
    const val BLOCKED_CHANNEL_SENDERS = "settings/blocked-channel-senders"
    const val REGION_MANAGEMENT = "settings/regions"
    const val LOCATION_PICKER = "settings/location-picker"
    const val OFFLINE_MAPS = "settings/offline-maps"
    const val OFFLINE_MAPS_PICK_REGION = "settings/offline-maps/pick-region"
    const val ABOUT = "settings/about"
    const val APPEARANCE = "settings/appearance"
    const val DANGER_ZONE = "settings/danger-zone"
    const val LICENSES = "settings/licenses"
    const val LICENSE_TEXT = "settings/licenses/text?path={path}&title={title}"

    fun licenseText(assetPath: String, title: String) =
        "settings/licenses/text?path=${Uri.encode(assetPath)}&title=${Uri.encode(title)}"
    fun directConversation(contactId: UUID) = "conversation/direct/$contactId"
    fun channelConversation(index: UByte) = "conversation/channel/${index.toInt()}"
    fun roomConversation(sessionId: UUID) = "conversation/room/$sessionId"
    fun messagePathMap(messageId: UUID) = "chat/message/$messageId/path-map"
    fun contactDetail(contactId: UUID) = "contact/$contactId"
    fun nodeAuth(contactId: UUID) = "node/$contactId/auth"
    fun roomStatus(sessionId: UUID) = "room/$sessionId/status"
    fun repeaterStatus(sessionId: UUID) = "repeater/$sessionId/status"
    fun repeaterNeighborsMap(sessionId: UUID) = "repeater/$sessionId/neighbors-map"
    fun nodeStatusHistory(sessionId: UUID) = "node/$sessionId/history"
    fun nodeTelemetryHistory(sessionId: UUID) = "node/$sessionId/telemetry-history"
    fun contactTelemetryHistory(contactId: UUID) = "contact/$contactId/telemetry-history"
    fun nodeLocationMap(fix: NodeLocationFix, name: String?) =
        "node/location-map?lat=${fix.latitude}&lon=${fix.longitude}" + (name?.let { "&name=${Uri.encode(it)}" } ?: "")
    fun nodeLocationHistoryMap(sessionId: UUID, initialSelectionId: UUID?) =
        "node/$sessionId/location-history-map" + (initialSelectionId?.let { "?initialSelectionId=$it" } ?: "")
    fun contactLocationHistoryMap(contactId: UUID, initialSelectionId: UUID?) =
        "contact/$contactId/location-history-map" + (initialSelectionId?.let { "?initialSelectionId=$it" } ?: "")
    fun repeaterNeighborChart(sessionId: UUID, prefix: ByteArray, name: String) =
        "repeater/$sessionId/neighbor-chart/${neighborPrefixHex(prefix)}?name=${Uri.encode(name)}"
    fun nodeCLI(sessionId: UUID, isRoom: Boolean) = "node/$sessionId/cli?isRoom=$isRoom"
    fun repeaterSettings(sessionId: UUID) = "repeater/$sessionId/settings"
    fun roomSettings(sessionId: UUID) = "room/$sessionId/settings"
    fun channelInfo(index: UByte) = "channel/${index.toInt()}/info"
    fun addChannelWithLink(link: String) = "$ADD_CHANNEL?link=${Uri.encode(link)}"
    fun addChannelWithHashtag(name: String) = "$ADD_CHANNEL?hashtag=${Uri.encode(name)}"
    fun addContactWithLink(link: String) = "$ADD_CONTACT?link=${Uri.encode(link)}"
    fun contactQrShare(name: String, publicKeyHex: String, contactTypeValue: Int) =
        "contacts/qr-share?name=${Uri.encode(name)}&publicKeyHex=$publicKeyHex&type=$contactTypeValue"
}

private data class TopLevelDestination(val route: String, @StringRes val label: Int, @DrawableRes val icon: Int)

private val topLevelDestinations = listOf(
    TopLevelDestination(MainRoute.CHATS, R.string.chats_title, R.drawable.ic_chat),
    TopLevelDestination(MainRoute.CONTACTS, R.string.common_contacts, R.drawable.ic_person),
    TopLevelDestination(MainRoute.MAP, R.string.nav_map, R.drawable.ic_map),
    TopLevelDestination(MainRoute.TOOLS, R.string.tools_title, R.drawable.ic_build),
    TopLevelDestination(MainRoute.SETTINGS, R.string.settings_title, R.drawable.ic_settings),
)

/**
 * Icon-only floating pill navigation bar: the selected tab gets a filled primary-tinted circle,
 * the rest are bare icons. Labels are dropped visually but kept as contentDescription for TalkBack.
 */
@Composable
private fun FloatingNavBar(
    destinations: List<TopLevelDestination>,
    currentRoute: String?,
    onSelect: (TopLevelDestination) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(64.dp),
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 1.dp,
            shadowElevation = 4.dp,
        ) {
            Row(
                Modifier.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                destinations.forEach { destination ->
                    val selected = currentRoute == destination.route
                    val background by animateColorAsState(
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                        label = "navItemBackground",
                    )
                    val tint by animateColorAsState(
                        if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        label = "navItemTint",
                    )
                    // The active tab claims a bigger share of the bar and shows its label; weights (not fixed
                    // widths) guarantee all five tabs always fit, whatever the label length or screen width.
                    val weight by animateFloatAsState(
                        if (selected) 2.4f else 1f,
                        spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium),
                        label = "navItemWeight",
                    )
                    Box(Modifier.weight(weight).height(48.dp), contentAlignment = Alignment.Center) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .widthIn(max = if (selected) Dp.Infinity else 48.dp)
                                .height(48.dp)
                                .clip(CircleShape)
                                .background(background)
                                .semantics { role = Role.Tab; this.selected = selected }
                                .clickable { onSelect(destination) }
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                painterResource(destination.icon),
                                contentDescription = if (selected) null else stringResource(destination.label),
                                tint = tint,
                                modifier = Modifier.size(24.dp),
                            )
                            if (selected) {
                                Text(
                                    stringResource(destination.label),
                                    color = tint,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false).padding(start = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The post-onboarding app shell — everything reachable from [MainRoute.CHATS]/[MainRoute.CONTACTS]/
 * [MainRoute.MAP]/[MainRoute.TOOLS]/[MainRoute.SETTINGS] via a bottom [FloatingNavBar]. Replaces the
 * single-purpose `ChatNavHost` now that more top-level sections (Contacts/Map/Settings/Tools —
 * PLAN.md's Phase 5 items 3/5/6/7) exist, each grown into this same bar rather than getting its own
 * ad hoc nav host. The bar is hidden on pushed (non-top-level) destinations — conversation, contact
 * detail, [MainRoute.NODE_AUTH], [MainRoute.ROOM_STATUS], [MainRoute.REPEATER_STATUS],
 * [MainRoute.REPEATER_NEIGHBORS_MAP], [MainRoute.NODE_STATUS_HISTORY],
 * [MainRoute.NODE_TELEMETRY_HISTORY], [MainRoute.CONTACT_TELEMETRY_HISTORY], [MainRoute.NODE_LOCATION_MAP],
 * [MainRoute.NODE_LOCATION_HISTORY_MAP], [MainRoute.CONTACT_LOCATION_HISTORY_MAP],
 * [MainRoute.REPEATER_NEIGHBOR_CHART], [MainRoute.MESSAGE_PATH_MAP], [MainRoute.NODE_CLI], [MainRoute.REPEATER_SETTINGS],
 * [MainRoute.ROOM_SETTINGS], [MainRoute.RX_LOG],
 * [MainRoute.LINE_OF_SIGHT], [MainRoute.TRACE_PATH], [MainRoute.NODE_DISCOVERY],
 * [MainRoute.DISCOVERY], [MainRoute.BLOCKED_CONTACTS], [MainRoute.CONFIG_EXPORT], [MainRoute.CONFIG_IMPORT],
 * [MainRoute.REGION_MANAGEMENT], [MainRoute.LOCATION_PICKER], [MainRoute.OFFLINE_MAPS],
 * [MainRoute.OFFLINE_MAPS_PICK_REGION], [MainRoute.ABOUT],
 * [MainRoute.LICENSES], and [MainRoute.LICENSE_TEXT] — the same way a
 * typical bottom-tab + stack-per-section app works,
 * except here everything shares one back stack for simplicity (no separate per-tab stacks yet).
 */
@Composable
fun MainScreen(
    connectionManager: ConnectionManager,
    locationProvider: LocationProvider,
    regionSelectionStore: RegionSelectionStore,
    regionResolver: RegionResolver,
    notificationPreferencesStore: NotificationPreferencesStore,
    staleNodeCleanupPreferencesStore: StaleNodeCleanupPreferencesStore,
    devicePreferenceStore: DevicePreferenceStore,
    offlineMapService: OfflineMapService,
    appBackupService: AppBackupService,
    themeService: ThemeService,
    prefs: SharedPreferences,
    pendingChannelLink: StateFlow<String?>,
    onConsumeChannelLink: () -> Unit,
    pendingNotificationRoute: StateFlow<String?>,
    onConsumeNotificationRoute: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val pendingLink by pendingChannelLink.collectAsStateWithLifecycle()
    LaunchedEffect(pendingLink) {
        pendingLink?.let { link ->
            navController.navigate(MainRoute.addChannelWithLink(link))
            onConsumeChannelLink()
        }
    }

    val pendingRoute by pendingNotificationRoute.collectAsStateWithLifecycle()
    LaunchedEffect(pendingRoute) {
        pendingRoute?.let { route ->
            navController.navigate(route)
            onConsumeNotificationRoute()
        }
    }

    Scaffold(
        bottomBar = {
            if (topLevelDestinations.any { it.route == currentRoute }) {
                FloatingNavBar(
                    destinations = topLevelDestinations,
                    currentRoute = currentRoute,
                    onSelect = { destination ->
                        navController.navigate(destination.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { padding ->
        // Every route below pushes its own Scaffold(topBar = TopAppBar(...)), each reading the same
        // ambient WindowInsets.statusBars/navigationBars this outer Scaffold already turned into
        // `padding` above. Without consumeWindowInsets, a bare `.padding(padding)` doesn't shrink
        // that ambient value, so every inner Scaffold re-reserves status/nav-bar space a second time
        // on top of the outer one — most visible on routes with no bottomBar of their own (detail
        // screens like ChannelInfoScreen/ContactDetailScreen), where nothing here consumes the outer
        // Scaffold's own bottom inset either, doubling it the same way.
        CompositionLocalProvider(LocalOpenDeviceSelection provides { navController.navigate(MainRoute.DEVICE_SELECTION) }) {
        NavHost(
            navController = navController,
            startDestination = MainRoute.CHATS,
            modifier = Modifier.accentBackdrop().padding(padding).consumeWindowInsets(padding),
            enterTransition = { screenEnter(initialState.destination.route, targetState.destination.route, forward = true) },
            exitTransition = { screenExit(initialState.destination.route, targetState.destination.route, forward = true) },
            popEnterTransition = { screenEnter(initialState.destination.route, targetState.destination.route, forward = false) },
            popExitTransition = { screenExit(initialState.destination.route, targetState.destination.route, forward = false) },
        ) {
            composable(MainRoute.CHATS) {
                ChatsListScreen(
                    connectionManager = connectionManager,
                    onOpenConversation = { conversation ->
                        // A disconnected room has no chat screen to open yet (see RoomConversationScreen's
                        // doc) — route to re-login instead, matching Swift's `ChatsView` tap handler.
                        val route = when (conversation) {
                            is Conversation.Direct -> MainRoute.directConversation(conversation.contact.id)
                            is Conversation.Channel -> MainRoute.channelConversation(conversation.channel.index)
                            is Conversation.Room -> if (conversation.session.isConnected) {
                                MainRoute.roomConversation(conversation.session.id)
                            } else {
                                conversation.contactId?.let { MainRoute.nodeAuth(it) }
                            }
                        }
                        route?.let { navController.navigate(it) }
                    },
                    onAddChannel = { navController.navigate(MainRoute.ADD_CHANNEL) },
                )
            }
            composable(MainRoute.CONTACTS) {
                ContactsListScreen(
                    connectionManager = connectionManager,
                    locationProvider = locationProvider,
                    onOpenContact = { contact -> navController.navigate(MainRoute.contactDetail(contact.id)) },
                    onOpenDiscovery = { navController.navigate(MainRoute.DISCOVERY) },
                    onOpenBlockedContacts = { navController.navigate(MainRoute.BLOCKED_CONTACTS) },
                    onOpenAddContact = { navController.navigate(MainRoute.ADD_CONTACT) },
                    onOpenShareMyContact = {
                        connectionManager.connectedDeviceRecord?.let { device ->
                            navController.navigate(MainRoute.contactQrShare(device.nodeName, device.publicKey.hexString, ContactType.CHAT.value.toInt()))
                        }
                    },
                )
            }
            composable(
                route = MainRoute.ADD_CONTACT_PATTERN,
                arguments = listOf(navArgument("link") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { backStack ->
                AddContactScreen(
                    connectionManager = connectionManager,
                    prefilledLink = backStack.arguments?.getString("link"),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = MainRoute.CONTACT_QR_SHARE,
                arguments = listOf(
                    navArgument("name") { type = NavType.StringType; defaultValue = "" },
                    navArgument("publicKeyHex") { type = NavType.StringType; defaultValue = "" },
                    navArgument("type") { type = NavType.IntType; defaultValue = ContactType.CHAT.value.toInt() },
                ),
            ) { backStack ->
                ContactQRShareScreen(
                    contactName = backStack.arguments?.getString("name") ?: "",
                    publicKeyHex = backStack.arguments?.getString("publicKeyHex") ?: "",
                    contactTypeValue = backStack.arguments?.getInt("type") ?: ContactType.CHAT.value.toInt(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(MainRoute.BLOCKED_CONTACTS) {
                BlockedContactsScreen(
                    connectionManager = connectionManager,
                    onOpenContact = { contact -> navController.navigate(MainRoute.contactDetail(contact.id)) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(MainRoute.DISCOVERY) {
                DiscoveryScreen(
                    connectionManager = connectionManager,
                    locationProvider = locationProvider,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(MainRoute.MAP) {
                MapScreen(
                    connectionManager = connectionManager,
                    locationProvider = locationProvider,
                    prefs = prefs,
                    onOpenConversation = { contactId -> navController.navigate(MainRoute.directConversation(contactId)) },
                    onOpenContactDetail = { contactId -> navController.navigate(MainRoute.contactDetail(contactId)) },
                    onOpenDiscovery = { navController.navigate(MainRoute.DISCOVERY) },
                )
            }
            composable(MainRoute.TOOLS) {
                ToolsScreen(
                    connectionManager = connectionManager,
                    onOpenRxLog = { navController.navigate(MainRoute.RX_LOG) },
                    onOpenLineOfSight = { navController.navigate(MainRoute.LINE_OF_SIGHT) },
                    onOpenTracePath = { navController.navigate(MainRoute.TRACE_PATH) },
                    onOpenNodeDiscovery = { navController.navigate(MainRoute.NODE_DISCOVERY) },
                )
            }
            composable(MainRoute.RX_LOG) {
                RxLogScreen(connectionManager = connectionManager, onBack = { navController.popBackStack() })
            }
            composable(MainRoute.LINE_OF_SIGHT) {
                LineOfSightScreen(
                    connectionManager = connectionManager,
                    locationProvider = locationProvider,
                    prefs = prefs,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(MainRoute.NODE_DISCOVERY) {
                NodeDiscoveryScreen(connectionManager = connectionManager, onBack = { navController.popBackStack() })
            }
            composable(MainRoute.TRACE_PATH) {
                TracePathListScreen(
                    connectionManager = connectionManager,
                    prefs = prefs,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(MainRoute.SETTINGS) {
                SettingsScreen(
                    connectionManager = connectionManager,
                    notificationPreferencesStore = notificationPreferencesStore,
                    staleNodeCleanupPreferencesStore = staleNodeCleanupPreferencesStore,
                    devicePreferenceStore = devicePreferenceStore,
                    locationProvider = locationProvider,
                    regionSelectionStore = regionSelectionStore,
                    regionResolver = regionResolver,
                    themeService = themeService,
                    prefs = prefs,
                    onOpenConfigExport = { navController.navigate(MainRoute.CONFIG_EXPORT) },
                    onOpenConfigImport = { navController.navigate(MainRoute.CONFIG_IMPORT) },
                    onOpenBackupRestore = { navController.navigate(MainRoute.BACKUP_RESTORE) },
                    onOpenDeviceSelection = { navController.navigate(MainRoute.DEVICE_SELECTION) },
                    onOpenBlockedChannelSenders = { navController.navigate(MainRoute.BLOCKED_CHANNEL_SENDERS) },
                    onOpenRegionManagement = { navController.navigate(MainRoute.REGION_MANAGEMENT) },
                    onOpenLocationPicker = { navController.navigate(MainRoute.LOCATION_PICKER) },
                    onOpenOfflineMaps = { navController.navigate(MainRoute.OFFLINE_MAPS) },
                    onOpenAppearance = { navController.navigate(MainRoute.APPEARANCE) },
                    onOpenDangerZone = { navController.navigate(MainRoute.DANGER_ZONE) },
                    onOpenAbout = { navController.navigate(MainRoute.ABOUT) },
                    onOpenLicenses = { navController.navigate(MainRoute.LICENSES) },
                )
            }
            composable(MainRoute.LOCATION_PICKER) {
                LocationPickerScreen(
                    connectionManager = connectionManager,
                    locationProvider = locationProvider,
                    devicePreferenceStore = devicePreferenceStore,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(MainRoute.OFFLINE_MAPS) {
                OfflineMapSettingsScreen(
                    offlineMapService = offlineMapService,
                    onBack = { navController.popBackStack() },
                    onAddRegion = { navController.navigate(MainRoute.OFFLINE_MAPS_PICK_REGION) },
                )
            }
            composable(MainRoute.OFFLINE_MAPS_PICK_REGION) {
                OfflineRegionPickerScreen(
                    offlineMapService = offlineMapService,
                    connectionManager = connectionManager,
                    onBack = { navController.popBackStack() },
                    onDownloadStarted = { navController.popBackStack() },
                )
            }
            composable(MainRoute.DANGER_ZONE) {
                DangerZoneScreen(
                    connectionManager = connectionManager,
                    notificationPreferencesStore = notificationPreferencesStore,
                    staleNodeCleanupPreferencesStore = staleNodeCleanupPreferencesStore,
                    devicePreferenceStore = devicePreferenceStore,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(MainRoute.APPEARANCE) {
                AppearanceScreen(themeService = themeService, onBack = { navController.popBackStack() })
            }
            composable(MainRoute.CONFIG_EXPORT) {
                NodeConfigExportScreen(connectionManager = connectionManager, onBack = { navController.popBackStack() })
            }
            composable(MainRoute.CONFIG_IMPORT) {
                NodeConfigImportScreen(connectionManager = connectionManager, onBack = { navController.popBackStack() })
            }
            composable(MainRoute.BACKUP_RESTORE) {
                BackupRestoreScreen(connectionManager = connectionManager, appBackupService = appBackupService, onBack = { navController.popBackStack() })
            }
            composable(MainRoute.DEVICE_SELECTION) {
                DeviceSelectionScreen(connectionManager = connectionManager, onBack = { navController.popBackStack() })
            }
            composable(MainRoute.BLOCKED_CHANNEL_SENDERS) {
                BlockedChannelSendersScreen(connectionManager = connectionManager, onBack = { navController.popBackStack() })
            }
            composable(MainRoute.REGION_MANAGEMENT) {
                RegionManagementScreen(connectionManager = connectionManager, onBack = { navController.popBackStack() })
            }
            composable(MainRoute.ABOUT) {
                AboutScreen(
                    onBack = { navController.popBackStack() },
                    onOpenLicense = { path, title -> navController.navigate(MainRoute.licenseText(path, title)) },
                    onOpenLicenses = { navController.navigate(MainRoute.LICENSES) },
                )
            }
            composable(MainRoute.LICENSES) {
                LicensesScreen(
                    onBack = { navController.popBackStack() },
                    onOpenLicense = { path, title -> navController.navigate(MainRoute.licenseText(path, title)) },
                )
            }
            composable(
                route = MainRoute.LICENSE_TEXT,
                arguments = listOf(
                    navArgument("path") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType },
                ),
            ) { backStack ->
                LicenseTextScreen(
                    assetPath = backStack.arguments?.getString("path").orEmpty(),
                    title = backStack.arguments?.getString("title").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = MainRoute.DIRECT_CONVERSATION,
                arguments = listOf(navArgument("contactId") { type = NavType.StringType }),
            ) { backStack ->
                val contactId = UUID.fromString(backStack.arguments?.getString("contactId"))
                ChatConversationScreen(
                    connectionManager = connectionManager,
                    target = ConversationTarget.Direct(contactId),
                    prefs = prefs,
                    onBack = { navController.popBackStack() },
                    onOpenChannelLink = { link -> navController.navigate(MainRoute.addChannelWithLink(link)) },
                    onOpenExistingChannel = { index -> navController.navigate(MainRoute.channelConversation(index)) },
                    onJoinHashtag = { name -> navController.navigate(MainRoute.addChannelWithHashtag(name)) },
                    onOpenContactShareLink = { link -> navController.navigate(MainRoute.addContactWithLink(link)) },
                    onOpenPathMap = { messageId -> navController.navigate(MainRoute.messagePathMap(messageId)) },
                )
            }
            composable(
                route = MainRoute.CHANNEL_CONVERSATION,
                arguments = listOf(navArgument("index") { type = NavType.IntType }),
            ) { backStack ->
                val index = (backStack.arguments?.getInt("index") ?: 0).toUByte()
                ChatConversationScreen(
                    connectionManager = connectionManager,
                    target = ConversationTarget.Channel(index),
                    prefs = prefs,
                    onBack = { navController.popBackStack() },
                    onOpenChannelInfo = { navController.navigate(MainRoute.channelInfo(index)) },
                    onOpenChannelLink = { link -> navController.navigate(MainRoute.addChannelWithLink(link)) },
                    onOpenExistingChannel = { targetIndex -> navController.navigate(MainRoute.channelConversation(targetIndex)) },
                    onJoinHashtag = { name -> navController.navigate(MainRoute.addChannelWithHashtag(name)) },
                    onOpenContactShareLink = { link -> navController.navigate(MainRoute.addContactWithLink(link)) },
                    onOpenConversation = { contactId -> navController.navigate(MainRoute.directConversation(contactId)) },
                    onOpenPathMap = { messageId -> navController.navigate(MainRoute.messagePathMap(messageId)) },
                )
            }
            composable(
                route = MainRoute.ROOM_CONVERSATION,
                arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
            ) { backStack ->
                val sessionId = UUID.fromString(backStack.arguments?.getString("sessionId"))
                RoomConversationScreen(
                    connectionManager = connectionManager,
                    sessionId = sessionId,
                    onBack = { navController.popBackStack() },
                    onOpenRoomInfo = { navController.navigate(MainRoute.roomStatus(sessionId)) },
                    onReconnect = { contactId -> navController.navigate(MainRoute.nodeAuth(contactId)) },
                    onOpenConversation = { contactId -> navController.navigate(MainRoute.directConversation(contactId)) },
                )
            }
            composable(
                route = MainRoute.ADD_CHANNEL_PATTERN,
                arguments = listOf(
                    navArgument("link") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("hashtag") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { backStack ->
                AddChannelScreen(
                    connectionManager = connectionManager,
                    initialLink = backStack.arguments?.getString("link"),
                    initialHashtag = backStack.arguments?.getString("hashtag"),
                    onJoined = { index ->
                        navController.popBackStack()
                        navController.navigate(MainRoute.channelConversation(index))
                    },
                    onCancel = { navController.popBackStack() },
                )
            }
            composable(
                route = MainRoute.CHANNEL_INFO,
                arguments = listOf(navArgument("index") { type = NavType.IntType }),
            ) { backStack ->
                val index = (backStack.arguments?.getInt("index") ?: 0).toUByte()
                ChannelInfoScreen(
                    connectionManager = connectionManager,
                    index = index,
                    onBack = { navController.popBackStack() },
                    onOpenRegionManagement = { navController.navigate(MainRoute.REGION_MANAGEMENT) },
                )
            }
            composable(
                route = MainRoute.CONTACT_DETAIL,
                arguments = listOf(navArgument("contactId") { type = NavType.StringType }),
            ) { backStack ->
                val contactId = UUID.fromString(backStack.arguments?.getString("contactId"))
                ContactDetailScreen(
                    connectionManager = connectionManager,
                    contactId = contactId,
                    onBack = { navController.popBackStack() },
                    onOpenConversation = { id -> navController.navigate(MainRoute.directConversation(id)) },
                    onOpenNodeAuth = { id -> navController.navigate(MainRoute.nodeAuth(id)) },
                    onOpenTelemetryHistory = { id -> navController.navigate(MainRoute.contactTelemetryHistory(id)) },
                    onOpenQRShare = { name, publicKeyHex, typeValue ->
                        navController.navigate(MainRoute.contactQrShare(name, publicKeyHex, typeValue))
                    },
                )
            }
            composable(
                route = MainRoute.NODE_AUTH,
                arguments = listOf(navArgument("contactId") { type = NavType.StringType }),
            ) { backStack ->
                val contactId = UUID.fromString(backStack.arguments?.getString("contactId"))
                NodeAuthScreen(
                    connectionManager = connectionManager,
                    contactId = contactId,
                    onBack = { navController.popBackStack() },
                    onAuthenticated = { session ->
                        val route = if (session.isRoom) MainRoute.roomStatus(session.id) else MainRoute.repeaterStatus(session.id)
                        navController.navigate(route) {
                            popUpTo(MainRoute.NODE_AUTH) { inclusive = true }
                        }
                    },
                )
            }
            composable(
                route = MainRoute.ROOM_STATUS,
                arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
            ) { backStack ->
                val sessionId = UUID.fromString(backStack.arguments?.getString("sessionId"))
                RoomStatusScreen(
                    connectionManager = connectionManager,
                    sessionId = sessionId,
                    onBack = { navController.popBackStack() },
                    onOpenHistory = { navController.navigate(MainRoute.nodeStatusHistory(sessionId)) },
                    onOpenTelemetryHistory = { navController.navigate(MainRoute.nodeTelemetryHistory(sessionId)) },
                    onViewLocationOnMap = { fix, name -> navController.navigate(MainRoute.nodeLocationMap(fix, name)) },
                    onOpenCLI = { navController.navigate(MainRoute.nodeCLI(sessionId, isRoom = true)) },
                    onOpenSettings = { navController.navigate(MainRoute.roomSettings(sessionId)) },
                )
            }
            composable(
                route = MainRoute.REPEATER_STATUS,
                arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
            ) { backStack ->
                val sessionId = UUID.fromString(backStack.arguments?.getString("sessionId"))
                RepeaterStatusScreen(
                    connectionManager = connectionManager,
                    sessionId = sessionId,
                    onBack = { navController.popBackStack() },
                    onViewNeighborsOnMap = { navController.navigate(MainRoute.repeaterNeighborsMap(sessionId)) },
                    onOpenNeighborChart = { name, prefix -> navController.navigate(MainRoute.repeaterNeighborChart(sessionId, prefix, name)) },
                    onOpenHistory = { navController.navigate(MainRoute.nodeStatusHistory(sessionId)) },
                    onOpenTelemetryHistory = { navController.navigate(MainRoute.nodeTelemetryHistory(sessionId)) },
                    onViewLocationOnMap = { fix, name -> navController.navigate(MainRoute.nodeLocationMap(fix, name)) },
                    onOpenCLI = { navController.navigate(MainRoute.nodeCLI(sessionId, isRoom = false)) },
                    onOpenSettings = { navController.navigate(MainRoute.repeaterSettings(sessionId)) },
                )
            }
            composable(
                route = MainRoute.NODE_CLI,
                arguments = listOf(
                    navArgument("sessionId") { type = NavType.StringType },
                    navArgument("isRoom") { type = NavType.BoolType; defaultValue = false },
                ),
            ) { backStack ->
                val sessionId = UUID.fromString(backStack.arguments?.getString("sessionId"))
                val isRoom = backStack.arguments?.getBoolean("isRoom") ?: false
                NodeCLIScreen(
                    connectionManager = connectionManager,
                    sessionId = sessionId,
                    isRoom = isRoom,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = MainRoute.REPEATER_SETTINGS,
                arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
            ) { backStack ->
                val sessionId = UUID.fromString(backStack.arguments?.getString("sessionId"))
                RepeaterSettingsScreen(
                    connectionManager = connectionManager,
                    sessionId = sessionId,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = MainRoute.ROOM_SETTINGS,
                arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
            ) { backStack ->
                val sessionId = UUID.fromString(backStack.arguments?.getString("sessionId"))
                RoomSettingsScreen(
                    connectionManager = connectionManager,
                    sessionId = sessionId,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = MainRoute.NODE_STATUS_HISTORY,
                arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
            ) { backStack ->
                val sessionId = UUID.fromString(backStack.arguments?.getString("sessionId"))
                NodeStatusHistoryScreen(
                    connectionManager = connectionManager,
                    sessionId = sessionId,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = MainRoute.NODE_TELEMETRY_HISTORY,
                arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
            ) { backStack ->
                val sessionId = UUID.fromString(backStack.arguments?.getString("sessionId"))
                TelemetryHistoryScreen(
                    connectionManager = connectionManager,
                    sessionId = sessionId,
                    onOpenLocationMap = { id -> navController.navigate(MainRoute.nodeLocationHistoryMap(sessionId, id)) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = MainRoute.CONTACT_TELEMETRY_HISTORY,
                arguments = listOf(navArgument("contactId") { type = NavType.StringType }),
            ) { backStack ->
                val contactId = UUID.fromString(backStack.arguments?.getString("contactId"))
                TelemetryHistoryOverviewScreen(
                    connectionManager = connectionManager,
                    contactId = contactId,
                    onOpenLocationMap = { id -> navController.navigate(MainRoute.contactLocationHistoryMap(contactId, id)) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = MainRoute.NODE_LOCATION_HISTORY_MAP,
                arguments = listOf(
                    navArgument("sessionId") { type = NavType.StringType },
                    navArgument("initialSelectionId") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { backStack ->
                val sessionId = UUID.fromString(backStack.arguments?.getString("sessionId"))
                val initialSelectionId = backStack.arguments?.getString("initialSelectionId")?.let(UUID::fromString)
                NodeLocationHistoryMapScreen(
                    connectionManager = connectionManager,
                    sessionId = sessionId,
                    initialSelectionId = initialSelectionId,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = MainRoute.CONTACT_LOCATION_HISTORY_MAP,
                arguments = listOf(
                    navArgument("contactId") { type = NavType.StringType },
                    navArgument("initialSelectionId") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { backStack ->
                val contactId = UUID.fromString(backStack.arguments?.getString("contactId"))
                val initialSelectionId = backStack.arguments?.getString("initialSelectionId")?.let(UUID::fromString)
                ContactLocationHistoryMapScreen(
                    connectionManager = connectionManager,
                    contactId = contactId,
                    initialSelectionId = initialSelectionId,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = MainRoute.NODE_LOCATION_MAP,
                arguments = listOf(
                    navArgument("lat") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("lon") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("name") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { backStack ->
                val latitude = backStack.arguments?.getString("lat")?.toDoubleOrNull() ?: return@composable
                val longitude = backStack.arguments?.getString("lon")?.toDoubleOrNull() ?: return@composable
                NodeLocationMapScreen(
                    latitude = latitude,
                    longitude = longitude,
                    name = backStack.arguments?.getString("name"),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = MainRoute.REPEATER_NEIGHBOR_CHART,
                arguments = listOf(
                    navArgument("sessionId") { type = NavType.StringType },
                    navArgument("prefix") { type = NavType.StringType },
                    navArgument("name") { type = NavType.StringType; defaultValue = "Unknown" },
                ),
            ) { backStack ->
                val sessionId = UUID.fromString(backStack.arguments?.getString("sessionId"))
                NeighborSnrChartScreen(
                    connectionManager = connectionManager,
                    sessionId = sessionId,
                    neighborPrefixHex = backStack.arguments?.getString("prefix").orEmpty(),
                    name = backStack.arguments?.getString("name") ?: "Unknown",
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = MainRoute.REPEATER_NEIGHBORS_MAP,
                arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
            ) { backStack ->
                val sessionId = UUID.fromString(backStack.arguments?.getString("sessionId"))
                NeighborSnrMapScreen(
                    connectionManager = connectionManager,
                    locationProvider = locationProvider,
                    sessionId = sessionId,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = MainRoute.MESSAGE_PATH_MAP,
                arguments = listOf(navArgument("messageId") { type = NavType.StringType }),
            ) { backStack ->
                val messageId = UUID.fromString(backStack.arguments?.getString("messageId"))
                MessagePathMapScreen(
                    connectionManager = connectionManager,
                    locationProvider = locationProvider,
                    messageId = messageId,
                    onBack = { navController.popBackStack() },
                )
            }
        }
        }
    }
}
