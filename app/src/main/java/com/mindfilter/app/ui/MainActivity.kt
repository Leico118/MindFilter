package com.mindfilter.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mindfilter.app.data.AppSettings
import com.mindfilter.app.ui.screens.AppsScreen
import com.mindfilter.app.ui.screens.CategoriesScreen
import com.mindfilter.app.ui.screens.CategoryDetailScreen
import com.mindfilter.app.ui.screens.FiltersScreen
import com.mindfilter.app.ui.screens.HomeScreen
import com.mindfilter.app.ui.screens.SettingsScreen
import com.mindfilter.app.ui.theme.LocalGlass
import com.mindfilter.app.ui.theme.MindFilterTheme
import kotlinx.coroutines.flow.MutableStateFlow
import com.mindfilter.app.update.UpdateNotifier
import com.mindfilter.app.update.Updater

class MainActivity : ComponentActivity() {

    /** Incrémenté à chaque retour dans l'appli pour rafraîchir l'état des autorisations. */
    private val resumeTick = MutableStateFlow(0)

    private val notificationPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.init(this)
        handleIntent(intent)
        enableEdgeToEdge()
        setContent {
            val settings by AppSettings.state.collectAsStateWithLifecycle()
            val tick by resumeTick.collectAsStateWithLifecycle()
            MindFilterTheme(settings.themeMode, settings.accent, settings.appStyle) {
                GlassBackground {
                    MindFilterRoot(tick)
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Ouverture depuis la notification « mise à jour disponible » : on va droit à la mise à jour. */
    private fun handleIntent(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(UpdateNotifier.EXTRA_OPEN_UPDATE, false) == true) {
            intent.removeExtra(UpdateNotifier.EXTRA_OPEN_UPDATE)
            UiRequests.openUpdate.value = true
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.value += 1
        // Vérification discrète d'une nouvelle version (au plus toutes les 6 h).
        val app = applicationContext
        Thread { try { UpdateNotifier.checkIfDue(app) } catch (_: Exception) { } }.start()
        // Android 13+ : demande (une seule fois) l'autorisation d'afficher la notification,
        // après le guide de démarrage pour ne pas tout demander d'un coup.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            !AppSettings.tutorial.value && UpdateNotifier.isEnabled(this) &&
            !UpdateNotifier.canNotify(this) && !UpdateNotifier.askedOnce(this)
        ) {
            UpdateNotifier.markAsked(this)
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** Demandes ponctuelles entre l'activité et l'interface. */
object UiRequests {
    /** Aller à la carte « Mise à jour » et lancer la vérification. */
    val openUpdate = MutableStateFlow(false)
    /** Faire défiler les Réglages jusqu'à la carte « Mise à jour ». */
    val scrollToUpdate = MutableStateFlow(false)
}

private data class Tab(val title: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("Accueil", Icons.Filled.Home),
    Tab("Catégories", Icons.Filled.Category),
    Tab("Applis", Icons.Filled.Apps),
    Tab("Filtres", Icons.Filled.Tune),
    Tab("Réglages", Icons.Filled.Settings),
)

@Composable
private fun MindFilterRoot(resumeTick: Int) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openCategory by rememberSaveable { mutableStateOf<String?>(null) }

    BackHandler(enabled = openCategory != null) { openCategory = null }
    BackHandler(enabled = openCategory == null && tab != 0) { tab = 0 }

    // Clavier ouvert : tout l'écran remonte au-dessus du clavier et la barre d'onglets
    // se cache pour laisser la place ; elle revient quand le clavier se ferme.
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    // Mémorise la position de défilement de chaque écran (et de chaque catégorie) :
    // en revenant en arrière, la liste réapparaît exactement là où on l'avait quittée.
    val screenStates = rememberSaveableStateHolder()

    // Ouverture depuis la notification de mise à jour : onglet Réglages + vérification.
    val openUpdate by UiRequests.openUpdate.collectAsStateWithLifecycle()
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    LaunchedEffect(openUpdate) {
        if (openUpdate) {
            openCategory = null
            tab = 4
            UiRequests.openUpdate.value = false
            UiRequests.scrollToUpdate.value = true
            Updater.check(appContext)
        }
    }

    // Guide de démarrage (première installation, ou relancé depuis Réglages).
    val tutorialOn by AppSettings.tutorial.collectAsStateWithLifecycle()
    var tutorialStep by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(tutorialOn) {
        if (tutorialOn) {
            tutorialStep = 0
            openCategory = null
        }
    }
    LaunchedEffect(tutorialOn, tutorialStep) {
        if (tutorialOn) TUTORIAL_STEPS.getOrNull(tutorialStep)?.tab?.let { tab = it }
    }

    Box(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        AnimatedContent(
            targetState = openCategory to tab,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "screen",
        ) { (cat, t) ->
            screenStates.SaveableStateProvider(if (cat != null) "cat-$cat" else "tab-$t") {
            when {
                cat != null -> CategoryDetailScreen(cat) { openCategory = null }
                t == 0 -> HomeScreen(resumeTick, onOpenCategories = { tab = 1 }, onOpenApps = { tab = 2 })
                t == 1 -> CategoriesScreen(onOpen = { openCategory = it })
                t == 2 -> AppsScreen()
                t == 3 -> FiltersScreen()
                else -> SettingsScreen(resumeTick)
            }
            }
        }
        if (openCategory == null && !imeVisible) {
            GlassTabBar(
                selected = tab,
                onSelect = { tab = it },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        if (tutorialOn) {
            TutorialOverlay(
                step = tutorialStep,
                onNext = {
                    if (tutorialStep >= TUTORIAL_STEPS.lastIndex) AppSettings.finishTutorial()
                    else tutorialStep++
                },
                onSkip = { AppSettings.finishTutorial() },
            )
        }
    }
}

@Composable
private fun GlassTabBar(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    val shape = RoundedCornerShape(30.dp)
    Row(
        modifier
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    if (g.tabTop != null && g.tabBottom != null) listOf(g.tabTop, g.tabBottom)
                    else if (g.dark) listOf(
                        androidx.compose.ui.graphics.Color(0xF22A2B33),
                        androidx.compose.ui.graphics.Color(0xF21E1F25),
                    ) else listOf(
                        androidx.compose.ui.graphics.Color(0xF7FFFFFF),
                        androidx.compose.ui.graphics.Color(0xEBF6F6FA),
                    ),
                ),
            )
            .border(1.dp, Brush.verticalGradient(listOf(g.glassBorderTop, g.glassBorderBottom)), shape)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        TABS.forEachIndexed { i, t ->
            val isSel = i == selected
            Column(
                Modifier
                    .weight(1f)
                    .tutorialAnchor("tab$i")
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (isSel) g.accent.copy(alpha = 0.13f) else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable(
                        interactionSource = MutableInteractionSource(),
                        indication = null,
                    ) { onSelect(i) }
                    .padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    t.icon,
                    contentDescription = t.title,
                    tint = if (isSel) g.accent else g.textSecondary,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    t.title,
                    fontSize = 10.5.sp,
                    color = if (isSel) g.accent else g.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}
