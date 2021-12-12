/*
 * Copyright (C) 2020 Patrick Goldinger
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.text

import android.view.KeyEvent
import androidx.core.text.isDigitsOnly
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.prefs.florisPreferenceModel
import dev.patrickgold.florisboard.assetManager
import dev.patrickgold.florisboard.databinding.FlorisboardBinding
import dev.patrickgold.florisboard.debug.LogTopic
import dev.patrickgold.florisboard.debug.flogError
import dev.patrickgold.florisboard.debug.flogInfo
import dev.patrickgold.florisboard.ime.core.EditorInstance
import dev.patrickgold.florisboard.ime.core.FlorisBoard
import dev.patrickgold.florisboard.ime.core.InputEventDispatcher
import dev.patrickgold.florisboard.ime.core.InputKeyEvent
import dev.patrickgold.florisboard.ime.core.InputKeyEventReceiver
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.nlp.TextProcessor
import dev.patrickgold.florisboard.ime.dictionary.DictionaryManager
import dev.patrickgold.florisboard.ime.keyboard.ImeOptions
import dev.patrickgold.florisboard.ime.keyboard.InputAttributes
import dev.patrickgold.florisboard.ime.keyboard.KeyboardState
import dev.patrickgold.florisboard.ime.text.gestures.GlideTypingManager
import dev.patrickgold.florisboard.ime.text.gestures.SwipeAction
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.key.KeyType
import dev.patrickgold.florisboard.ime.text.key.KeyVariation
import dev.patrickgold.florisboard.ime.text.key.UtilityKeyAction
import dev.patrickgold.florisboard.ime.keyboard.KeyboardMode
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyboardCache
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyboardIconSet
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyboardView
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.res.FlorisRef
import dev.patrickgold.florisboard.subtypeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * TextInputManager is responsible for managing everything which is related to text input. All of
 * the following count as text input: character, numeric (+advanced), phone and symbol layouts.
 *
 * All of the UI for the different keyboard layouts are kept under the same container element and
 * are separated from media-related UI. The core [FlorisBoard] will pass any event defined in
 * [FlorisBoard.EventListener] through to this class.
 *
 * TextInputManager is also the hub in the communication between the system, the active editor
 * instance and the Smartbar.
 */
class TextInputManager private constructor() : CoroutineScope by MainScope(), InputKeyEventReceiver,
    FlorisBoard.EventListener, EditorInstance.WordHistoryChangedListener {

    private val florisboard get() = FlorisBoard.getInstance()
    private val prefs by florisPreferenceModel()
    private val keyboardManager by florisboard.keyboardManager()
    private val subtypeManager by florisboard.subtypeManager()

    private val activeState get() = keyboardManager.activeState
    var isGlidePostEffect: Boolean = false
    var symbolsWithSpaceAfter: List<String> = listOf()
    private val activeEditorInstance: EditorInstance
        get() = florisboard.activeEditorInstance

    val keyboards = TextKeyboardCache()
    private var textInputKeyboardView: TextKeyboardView? = null
    lateinit var textKeyboardIconSet: TextKeyboardIconSet
        private set
    private val dictionaryManager: DictionaryManager get() = DictionaryManager.default()
    val inputEventDispatcher: InputEventDispatcher = InputEventDispatcher.new(
        repeatableKeyCodes = intArrayOf(
            KeyCode.ARROW_DOWN,
            KeyCode.ARROW_LEFT,
            KeyCode.ARROW_RIGHT,
            KeyCode.ARROW_UP,
            KeyCode.DELETE,
            KeyCode.FORWARD_DELETE
        )
    )

    private var newCapsState: Boolean = false
    private var isNumberRowVisible: Boolean = false

    companion object {
        private var instance: TextInputManager? = null

        @Synchronized
        fun getInstance(): TextInputManager {
            if (instance == null) {
                instance = TextInputManager()
            }
            return instance!!
        }
    }

    init {
        florisboard.addEventListener(this)
    }

    /**
     * Non-UI-related setup + preloading of all required computed layouts (asynchronous in the
     * background).
     */
    override fun onCreate() {
        flogInfo(LogTopic.IMS_EVENTS)

        val data =
            florisboard.assetManager().value.loadTextAsset(FlorisRef.assets("ime/text/symbols-with-space.json")).getOrThrow()
        val json = JSONArray(data)
        this.symbolsWithSpaceAfter = List(json.length()){ json.getString(it) }
        textKeyboardIconSet = TextKeyboardIconSet.new(florisboard)
        inputEventDispatcher.keyEventReceiver = this
        isNumberRowVisible = prefs.keyboard.numberRow.get()
        activeEditorInstance.wordHistoryChangedListener = this
        var subtypes = subtypeManager.subtypes()
        if (subtypes.isEmpty()) {
            subtypes = listOf(Subtype.DEFAULT)
        }
        for (subtype in subtypes) {
            for (mode in KeyboardMode.values()) {
                //keyboards.set(mode, subtype, keyboard = keyboardManager.computeKeyboardAsync(mode, subtype))
            }
        }

        prefs.advanced.forcePrivateMode.observe(florisboard) {
            activeState.updateIsPrivate(it)
        }

        subtypeManager.activeSubtype.observe(florisboard) { newSubtype ->
            launch {
                if (activeState.isComposingEnabled) {
                    launch(Dispatchers.IO) {
                        dictionaryManager.prepareDictionaries(newSubtype)
                    }
                }
                if (prefs.glide.enabled.get()) {
                    GlideTypingManager.getInstance(florisboard).setWordData(newSubtype)
                }
                setActiveKeyboard(getActiveKeyboardMode(), newSubtype)
            }
            isGlidePostEffect = false
        }
    }

    override fun onInitializeInputUi(uiBinding: FlorisboardBinding) {
        flogInfo(LogTopic.IMS_EVENTS)

        textInputKeyboardView = uiBinding.text.mainKeyboardView.also {
            //it.setComputingEvaluator(DefaultComputingEvaluator)
            //it.sync()
        }

        //smartbarView = uiBinding.text.smartbar.root.also {
        //    it.setEventListener(this)
        //    it.sync()
        //}

        setActiveKeyboardMode(getActiveKeyboardMode())
    }

    /**
     * Cancels all coroutines and cleans up.
     */
    override fun onDestroy() {
        flogInfo(LogTopic.IMS_EVENTS)

        //textInputKeyboardView?.setComputingEvaluator(null)
        textInputKeyboardView = null
        keyboards.clear()

        inputEventDispatcher.keyEventReceiver = null
        inputEventDispatcher.close()

        dictionaryManager.unloadUserDictionariesIfNecessary()

        activeEditorInstance.wordHistoryChangedListener = null

        cancel()
        instance = null
    }

    /**
     * Evaluates the [KeyboardState.keyboardMode], [KeyboardState.keyVariation] and [KeyboardState.isComposingEnabled]
     * property values when starting to interact with a input editor. Also resets the composing
     * texts and sets the initial caps mode accordingly.
     */
    override fun onStartInputView(instance: EditorInstance, restarting: Boolean) {
        val keyboardMode = when (activeState.inputAttributes.type) {
            InputAttributes.Type.NUMBER -> {
                activeState.keyVariation = KeyVariation.NORMAL
                KeyboardMode.NUMERIC
            }
            InputAttributes.Type.PHONE -> {
                activeState.keyVariation = KeyVariation.NORMAL
                KeyboardMode.PHONE
            }
            InputAttributes.Type.TEXT -> {
                activeState.keyVariation = when (activeState.inputAttributes.variation) {
                    InputAttributes.Variation.EMAIL_ADDRESS,
                    InputAttributes.Variation.WEB_EMAIL_ADDRESS -> {
                        KeyVariation.EMAIL_ADDRESS
                    }
                    InputAttributes.Variation.PASSWORD,
                    InputAttributes.Variation.VISIBLE_PASSWORD,
                    InputAttributes.Variation.WEB_PASSWORD -> {
                        KeyVariation.PASSWORD
                    }
                    InputAttributes.Variation.URI -> {
                        KeyVariation.URI
                    }
                    else -> {
                        KeyVariation.NORMAL
                    }
                }
                KeyboardMode.CHARACTERS
            }
            else -> {
                activeState.keyVariation = KeyVariation.NORMAL
                KeyboardMode.CHARACTERS
            }
        }
        activeState.isComposingEnabled = when (keyboardMode) {
            KeyboardMode.NUMERIC,
            KeyboardMode.PHONE,
            KeyboardMode.PHONE2 -> false
            else -> activeState.keyVariation != KeyVariation.PASSWORD &&
                    prefs.suggestion.enabled.get()// &&
            //!instance.inputAttributes.flagTextAutoComplete &&
            //!instance.inputAttributes.flagTextNoSuggestions
        } && run {
            if (prefs.devtools.overrideWordSuggestionsMinHeapRestriction.get()) {
                true
            } else {
                val runtime = Runtime.getRuntime()
                val maxHeapSizeInMB = runtime.maxMemory() / 1048576L
                maxHeapSizeInMB >= 256
            }
        }
        val newIsNumberRowVisible = prefs.keyboard.numberRow.get()
        if (isNumberRowVisible != newIsNumberRowVisible) {
            keyboards.clear(KeyboardMode.CHARACTERS)
            isNumberRowVisible = newIsNumberRowVisible
        }
        setActiveKeyboardMode(keyboardMode)
        instance.composingEnabledChanged()
        activeState.updateIsPrivate()
        if (!prefs.correction.rememberCapsLockState.get()) {
            activeState.capsLock = false
        }
        isGlidePostEffect = false
        updateCapsState()
        //smartbarView?.setCandidateSuggestionWords(System.nanoTime(), null)
    }

    override fun onWindowShown() {
        launch(Dispatchers.Default) {
            dictionaryManager.loadUserDictionariesIfNecessary()
        }
        textInputKeyboardView?.sync()
        //smartbarView?.sync()
    }

    /**
     * Gets the active keyboard mode.
     *
     * @return The active keyboard mode.
     */
    fun getActiveKeyboardMode(): KeyboardMode {
        return activeState.keyboardMode
    }

    /**
     * Sets the active keyboard mode and updates the [KeyboardState.isQuickActionsVisible] state.
     */
    private fun setActiveKeyboardMode(mode: KeyboardMode) {
        activeState.batchEdit {
            it.keyboardMode = mode
            it.isManualSelectionMode = false
            it.isManualSelectionModeStart = false
            it.isManualSelectionModeEnd = false
            it.isQuickActionsVisible = false
        }
        setActiveKeyboard(mode, subtypeManager.activeSubtype())
    }

    private fun setActiveKeyboard(mode: KeyboardMode, subtype: Subtype) = launch(Dispatchers.IO) {
        //val activeKeyboard = keyboards.getOrElseAsync(mode, subtype) {
        //    keyboardManager.computeKeyboardAsync(
        //        keyboardMode = mode,
        //        subtype = subtype
        //    ).await()
        //}.await()
        withContext(Dispatchers.Main) {
            //textInputKeyboardView?.setComputedKeyboard(activeKeyboard)
        }
    }

    /**
     * Main logic point for processing cursor updates as well as parsing the current composing word
     * and passing this info on to the [SmartbarView] to turn it into candidate suggestions.
     */
    override fun onUpdateSelection() {
        if (!inputEventDispatcher.isPressed(KeyCode.SHIFT)) {
            updateCapsState()
        }
    }

    override fun onWordHistoryChanged(
        currentWord: EditorInstance.Region?,
        wordsBeforeCurrent: List<EditorInstance.Region>,
        wordsAfterCurrent: List<EditorInstance.Region>
    ) {
        if (currentWord == null || !currentWord.isValid) {
            //smartbarView?.setCandidateSuggestionWords(System.nanoTime(), null)
            return
        }
        if (activeState.isComposingEnabled && !inputEventDispatcher.isPressed(KeyCode.DELETE) && !isGlidePostEffect) {
            launch(Dispatchers.Default) {
                val startTime = System.nanoTime()
                dictionaryManager.suggest(
                    currentWord = currentWord.text,
                    preceidingWords = listOf(),
                    subtype = subtypeManager.activeSubtype(),
                    allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
                    maxSuggestionCount = 16
                ) { suggestions ->
                    withContext(Dispatchers.Main) {
                        //smartbarView?.setCandidateSuggestionWords(startTime, suggestions)
                        //smartbarView?.updateCandidateSuggestionCapsState()
                    }
                }
                if (BuildConfig.DEBUG) {
                    val elapsed = (System.nanoTime() - startTime) / 1000.0
                    flogInfo { "sugg fetch time: $elapsed us" }
                }
            }
        }
    }

    /**
     * Updates the current caps state according to the [EditorInstance.cursorCapsMode], while
     * respecting [KeyboardState.capsLock] property and the correction.autoCapitalization preference.
     */
    private fun updateCapsState() {
        if (!activeState.capsLock) {
            activeState.shiftLock = prefs.correction.autoCapitalization.get() &&
                    activeEditorInstance.cursorCapsMode != InputAttributes.CapsMode.NONE
        }
    }

    /**
     * Executes a given [SwipeAction]. Ignores any [SwipeAction] but the ones relevant for this
     * class.
     */
    fun executeSwipeAction(swipeAction: SwipeAction) {
        val keyData = when (swipeAction) {
            SwipeAction.CYCLE_TO_PREVIOUS_KEYBOARD_MODE -> when (getActiveKeyboardMode()) {
                KeyboardMode.CHARACTERS -> TextKeyData.VIEW_NUMERIC_ADVANCED
                KeyboardMode.NUMERIC_ADVANCED -> TextKeyData.VIEW_SYMBOLS2
                KeyboardMode.SYMBOLS2 -> TextKeyData.VIEW_SYMBOLS
                else -> TextKeyData.VIEW_CHARACTERS
            }
            SwipeAction.CYCLE_TO_NEXT_KEYBOARD_MODE -> when (getActiveKeyboardMode()) {
                KeyboardMode.CHARACTERS -> TextKeyData.VIEW_SYMBOLS
                KeyboardMode.SYMBOLS -> TextKeyData.VIEW_SYMBOLS2
                KeyboardMode.SYMBOLS2 -> TextKeyData.VIEW_NUMERIC_ADVANCED
                else -> TextKeyData.VIEW_CHARACTERS
            }
            SwipeAction.DELETE_WORD -> TextKeyData.DELETE_WORD
            SwipeAction.INSERT_SPACE -> TextKeyData.SPACE
            SwipeAction.MOVE_CURSOR_DOWN -> TextKeyData.ARROW_DOWN
            SwipeAction.MOVE_CURSOR_UP -> TextKeyData.ARROW_UP
            SwipeAction.MOVE_CURSOR_LEFT -> TextKeyData.ARROW_LEFT
            SwipeAction.MOVE_CURSOR_RIGHT -> TextKeyData.ARROW_RIGHT
            SwipeAction.MOVE_CURSOR_START_OF_LINE -> TextKeyData.MOVE_START_OF_LINE
            SwipeAction.MOVE_CURSOR_END_OF_LINE -> TextKeyData.MOVE_END_OF_LINE
            SwipeAction.MOVE_CURSOR_START_OF_PAGE -> TextKeyData.MOVE_START_OF_PAGE
            SwipeAction.MOVE_CURSOR_END_OF_PAGE -> TextKeyData.MOVE_END_OF_PAGE
            SwipeAction.SHIFT -> TextKeyData.SHIFT
            SwipeAction.REDO -> TextKeyData.REDO
            SwipeAction.UNDO -> TextKeyData.UNDO
            SwipeAction.SWITCH_TO_CLIPBOARD_CONTEXT -> TextKeyData.IME_UI_MODE_CLIPBOARD
            SwipeAction.SHOW_INPUT_METHOD_PICKER -> TextKeyData.SYSTEM_INPUT_METHOD_PICKER
            else -> null
        }
        if (keyData != null) {
            inputEventDispatcher.send(InputKeyEvent.downUp(keyData))
        }
    }

    //override fun onSmartbarBackButtonPressed() {
    //    florisboard.inputFeedbackController.keyPress()
    //    setActiveKeyboardMode(KeyboardMode.CHARACTERS)
    //}
//
    //override fun onSmartbarCandidatePressed(word: String) {
    //    florisboard.inputFeedbackController.keyPress()
    //    isGlidePostEffect = false
    //    activeEditorInstance.commitCompletion(word)
    //}
//
    //override fun onSmartbarClipboardCandidatePressed(clipboardItem: ClipboardItem) {
    //    florisboard.inputFeedbackController.keyPress()
    //    isGlidePostEffect = false
    //    activeEditorInstance.commitClipboardItem(clipboardItem)
    //}
//
    //override fun onSmartbarPrivateModeButtonClicked() {
    //    florisboard.inputFeedbackController.keyPress()
    //    Toast.makeText(florisboard, R.string.private_mode_dialog__title, Toast.LENGTH_LONG).show()
    //}
//
    //override fun onSmartbarQuickActionPressed(quickActionId: Int) {
    //    florisboard.inputFeedbackController.keyPress()
    //    when (quickActionId) {
    //        R.id.quick_action_toggle -> {
    //            activeState.isQuickActionsVisible = !activeState.isQuickActionsVisible
    //            smartbarView?.updateKeyboardState(activeState)
    //            return
    //        }
    //        R.id.quick_action_switch_to_editing_context -> {
    //            if (activeState.keyboardMode == KeyboardMode.EDITING) {
    //                setActiveKeyboardMode(KeyboardMode.CHARACTERS)
    //            } else {
    //                setActiveKeyboardMode(KeyboardMode.EDITING)
    //            }
    //        }
    //        R.id.quick_action_switch_to_media_context -> florisboard.setActiveInput(R.id.media_input)
    //        R.id.quick_action_open_settings -> florisboard.launchSettings()
    //        R.id.quick_action_one_handed_toggle -> florisboard.toggleOneHandedMode(isRight = true)
    //        R.id.quick_action_undo -> {
    //            inputEventDispatcher.send(InputKeyEvent.downUp(TextKeyData.UNDO))
    //            return
    //        }
    //        R.id.quick_action_redo -> {
    //            inputEventDispatcher.send(InputKeyEvent.downUp(TextKeyData.REDO))
    //            return
    //        }
    //    }
    //    activeState.isQuickActionsVisible = false
    //    smartbarView?.updateKeyboardState(activeState)
    //}

    /**
     * Handles a [KeyCode.DELETE] event.
     */
    private fun handleDelete() {
        if (isGlidePostEffect){
            handleDeleteWord()
            isGlidePostEffect = false
            //smartbarView?.setCandidateSuggestionWords(System.nanoTime(), null)
        } else {
            activeState.batchEdit {
                it.isManualSelectionMode = false
                it.isManualSelectionModeStart = false
                it.isManualSelectionModeEnd = false
            }
            activeEditorInstance.deleteBackwards()
        }
    }

    /**
     * Handles a [KeyCode.DELETE_WORD] event.
     */
    private fun handleDeleteWord() {
        activeState.batchEdit {
            it.isManualSelectionMode = false
            it.isManualSelectionModeStart = false
            it.isManualSelectionModeEnd = false
        }
        isGlidePostEffect = false
        activeEditorInstance.deleteWordBackwards()
    }

    /**
     * Handles a [KeyCode.ENTER] event.
     */
    private fun handleEnter() {
        if (activeState.imeOptions.flagNoEnterAction) {
            activeEditorInstance.performEnter()
        } else {
            when (activeState.imeOptions.enterAction) {
                ImeOptions.EnterAction.DONE,
                ImeOptions.EnterAction.GO,
                ImeOptions.EnterAction.NEXT,
                ImeOptions.EnterAction.PREVIOUS,
                ImeOptions.EnterAction.SEARCH,
                ImeOptions.EnterAction.SEND -> {
                    activeEditorInstance.performEnterAction(activeState.imeOptions.enterAction)
                }
                else -> activeEditorInstance.performEnter()
            }
        }
        isGlidePostEffect = false
    }

    /**
     * Handles a [KeyCode.LANGUAGE_SWITCH] event. Also handles if the language switch should cycle
     * FlorisBoard internal or system-wide.
     */
    private fun handleLanguageSwitch() {
        when (prefs.keyboard.utilityKeyAction.get()) {
            UtilityKeyAction.DYNAMIC_SWITCH_LANGUAGE_EMOJIS,
            UtilityKeyAction.SWITCH_LANGUAGE -> subtypeManager.switchToNextSubtype()
            else -> florisboard.switchToNextKeyboard()
        }
    }

    /**
     * Handles a [KeyCode.SHIFT] down event.
     */
    private fun handleShiftDown(ev: InputKeyEvent) {
        if (ev.isConsecutiveEventOf(inputEventDispatcher.lastKeyEventDown, prefs.keyboard.longPressDelay.get().toLong())) {
            newCapsState = true
            activeState.shiftLock = true
            activeState.capsLock = true
        } else {
            newCapsState = !activeState.shiftLock
            activeState.shiftLock = true
            activeState.capsLock = false
        }
    }

    /**
     * Handles a [KeyCode.SHIFT] up event.
     */
    private fun handleShiftUp() {
        activeState.shiftLock = newCapsState
    }

    /**
     * Handles a [KeyCode.SHIFT] cancel event.
     */
    private fun handleShiftCancel() {
        activeState.shiftLock = false
        activeState.capsLock = false
    }

    /**
     * Handles a [KeyCode.SHIFT] up event.
     */
    private fun handleShiftLock() {
        val lastKeyEvent = inputEventDispatcher.lastKeyEventDown ?: return
        if (lastKeyEvent.data.code == KeyCode.SHIFT && lastKeyEvent.action == InputKeyEvent.Action.DOWN) {
            newCapsState = true
            activeState.shiftLock = true
            activeState.capsLock = true
        }
    }

    /**
     * Handles a [KeyCode.KANA_SWITCHER] event
     */
    private fun handleKanaSwitch() {
        activeState.batchEdit {
            it.isKanaKata = !it.isKanaKata
            it.isCharHalfWidth = false
        }
    }

    /**
     * Handles a [KeyCode.KANA_HIRA] event
     */
    private fun handleKanaHira() {
        activeState.batchEdit {
            it.isKanaKata = false
            it.isCharHalfWidth = false
        }
    }

    /**
     * Handles a [KeyCode.KANA_KATA] event
     */
    private fun handleKanaKata() {
        activeState.batchEdit {
            it.isKanaKata = true
            it.isCharHalfWidth = false
        }
    }

    /**
     * Handles a [KeyCode.KANA_HALF_KATA] event
     */
    private fun handleKanaHalfKata() {
        activeState.batchEdit {
            it.isKanaKata = true
            it.isCharHalfWidth = true
        }
    }

    /**
     * Handles a [KeyCode.CHAR_WIDTH_SWITCHER] event
     */
    private fun handleCharWidthSwitch() {
        activeState.isCharHalfWidth = !activeState.isCharHalfWidth
    }

    /**
     * Handles a [KeyCode.CHAR_WIDTH_SWITCHER] event
     */
    private fun handleCharWidthFull() {
        activeState.isCharHalfWidth = false
    }

    /**
     * Handles a [KeyCode.CHAR_WIDTH_SWITCHER] event
     */
    private fun handleCharWidthHalf() {
        activeState.isCharHalfWidth = true
    }

    /**
     * Handles a [KeyCode.SPACE] event. Also handles the auto-correction of two space taps if
     * enabled by the user.
     */
    private fun handleSpace(ev: InputKeyEvent) {
        if (prefs.keyboard.spaceBarSwitchesToCharacters.get() && getActiveKeyboardMode() != KeyboardMode.CHARACTERS) {
            setActiveKeyboardMode(KeyboardMode.CHARACTERS)
        }
        if (prefs.correction.doubleSpacePeriod.get()) {
            if (ev.isConsecutiveEventOf(inputEventDispatcher.lastKeyEventUp, prefs.keyboard.longPressDelay.get().toLong())) {
                val text = activeEditorInstance.getTextBeforeCursor(2)
                if (text.length == 2 && !text.matches("""[.!?‽\s][\s]""".toRegex())) {
                    activeEditorInstance.deleteBackwards()
                    activeEditorInstance.commitText(".")
                }
            }
        }
        isGlidePostEffect = false
        activeEditorInstance.commitText(KeyCode.SPACE.toChar().toString())
    }

    /**
     * Handles [KeyCode] arrow and move events, behaves differently depending on text selection.
     */
    private fun handleArrow(code: Int, count: Int) = activeEditorInstance.apply {
        val isShiftPressed = activeState.isManualSelectionMode || inputEventDispatcher.isPressed(KeyCode.SHIFT)
        when (code) {
            KeyCode.ARROW_DOWN -> {
                if (!selection.isSelectionMode && activeState.isManualSelectionMode) {
                    activeState.isManualSelectionModeStart = false
                    activeState.isManualSelectionModeEnd = true
                }
                sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN, meta(shift = isShiftPressed), count)
            }
            KeyCode.ARROW_LEFT -> {
                if (!selection.isSelectionMode && activeState.isManualSelectionMode) {
                    activeState.isManualSelectionModeStart = true
                    activeState.isManualSelectionModeEnd = false
                }
                sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT, meta(shift = isShiftPressed), count)
            }
            KeyCode.ARROW_RIGHT -> {
                if (!selection.isSelectionMode && activeState.isManualSelectionMode) {
                    activeState.isManualSelectionModeStart = false
                    activeState.isManualSelectionModeEnd = true
                }
                sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT, meta(shift = isShiftPressed), count)
            }
            KeyCode.ARROW_UP -> {
                if (!selection.isSelectionMode && activeState.isManualSelectionMode) {
                    activeState.isManualSelectionModeStart = true
                    activeState.isManualSelectionModeEnd = false
                }
                sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_UP, meta(shift = isShiftPressed), count)
            }
            KeyCode.MOVE_START_OF_PAGE -> {
                if (!selection.isSelectionMode && activeState.isManualSelectionMode) {
                    activeState.isManualSelectionModeStart = true
                    activeState.isManualSelectionModeEnd = false
                }
                sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_UP, meta(alt = true, shift = isShiftPressed), count)
            }
            KeyCode.MOVE_END_OF_PAGE -> {
                if (!selection.isSelectionMode && activeState.isManualSelectionMode) {
                    activeState.isManualSelectionModeStart = false
                    activeState.isManualSelectionModeEnd = true
                }
                sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN, meta(alt = true, shift = isShiftPressed), count)
            }
            KeyCode.MOVE_START_OF_LINE -> {
                if (!selection.isSelectionMode && activeState.isManualSelectionMode) {
                    activeState.isManualSelectionModeStart = true
                    activeState.isManualSelectionModeEnd = false
                }
                sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT, meta(alt = true, shift = isShiftPressed), count)
            }
            KeyCode.MOVE_END_OF_LINE -> {
                if (!selection.isSelectionMode && activeState.isManualSelectionMode) {
                    activeState.isManualSelectionModeStart = false
                    activeState.isManualSelectionModeEnd = true
                }
                sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT, meta(alt = true, shift = isShiftPressed), count)
            }
        }
        isGlidePostEffect = false
    }

    /**
     * Handles a [KeyCode.CLIPBOARD_SELECT] event.
     */
    private fun handleClipboardSelect() = activeEditorInstance.apply {
        activeState.isManualSelectionMode = if (selection.isSelectionMode) {
            if (activeState.isManualSelectionMode && activeState.isManualSelectionModeStart) {
                selection.updateAndNotify(selection.start, selection.start)
            } else {
                selection.updateAndNotify(selection.end, selection.end)
            }
            false
        } else {
            !activeState.isManualSelectionMode
        }
        isGlidePostEffect = false
    }

    override fun onInputKeyDown(ev: InputKeyEvent) {
        val data = ev.data
        when (data.code) {
            KeyCode.INTERNAL_BATCH_EDIT -> {
                florisboard.beginInternalBatchEdit()
                return
            }
            KeyCode.SHIFT -> {
                handleShiftDown(ev)
            }
        }
    }

    override fun onInputKeyUp(ev: InputKeyEvent) {
        val data = ev.data
        when (data.code) {
            KeyCode.ARROW_DOWN,
            KeyCode.ARROW_LEFT,
            KeyCode.ARROW_RIGHT,
            KeyCode.ARROW_UP,
            KeyCode.MOVE_START_OF_PAGE,
            KeyCode.MOVE_END_OF_PAGE,
            KeyCode.MOVE_START_OF_LINE,
            KeyCode.MOVE_END_OF_LINE -> if (ev.action == InputKeyEvent.Action.DOWN_UP || ev.action == InputKeyEvent.Action.REPEAT) {
                handleArrow(data.code, ev.count)
            } else {
                handleArrow(data.code, 1)
            }
            KeyCode.CHAR_WIDTH_SWITCHER -> handleCharWidthSwitch()
            KeyCode.CHAR_WIDTH_FULL -> handleCharWidthFull()
            KeyCode.CHAR_WIDTH_HALF -> handleCharWidthHalf()
            KeyCode.CLIPBOARD_CUT -> activeEditorInstance.performClipboardCut()
            KeyCode.CLIPBOARD_COPY -> activeEditorInstance.performClipboardCopy()
            KeyCode.CLIPBOARD_PASTE -> activeEditorInstance.performClipboardPaste()
            KeyCode.CLIPBOARD_SELECT -> handleClipboardSelect()
            KeyCode.CLIPBOARD_SELECT_ALL -> activeEditorInstance.performClipboardSelectAll()
            KeyCode.DELETE -> handleDelete()
            KeyCode.DELETE_WORD -> handleDeleteWord()
            KeyCode.ENTER -> handleEnter()
            KeyCode.INTERNAL_BATCH_EDIT -> {
                florisboard.endInternalBatchEdit()
                return
            }
            KeyCode.LANGUAGE_SWITCH -> handleLanguageSwitch()
            KeyCode.REDO -> activeEditorInstance.performRedo()
            KeyCode.SETTINGS -> florisboard.launchSettings()
            KeyCode.SHIFT -> handleShiftUp()
            KeyCode.CAPS_LOCK -> handleShiftLock()
            KeyCode.KANA_SWITCHER -> handleKanaSwitch()
            KeyCode.KANA_HIRA -> handleKanaHira()
            KeyCode.KANA_KATA -> handleKanaKata()
            KeyCode.KANA_HALF_KATA -> handleKanaHalfKata()
            KeyCode.SYSTEM_INPUT_METHOD_PICKER -> florisboard.imeManager?.showInputMethodPicker()
            KeyCode.SPACE -> handleSpace(ev)
            KeyCode.IME_UI_MODE_MEDIA -> florisboard.setActiveInput(R.id.media_input)
            KeyCode.IME_UI_MODE_TEXT -> florisboard.setActiveInput(R.id.text_input, forceSwitchToCharacters = true)
            //KeyCode.TOGGLE_ONE_HANDED_MODE_LEFT -> florisboard.toggleOneHandedMode(isRight = false)
            //KeyCode.TOGGLE_ONE_HANDED_MODE_RIGHT -> florisboard.toggleOneHandedMode(isRight = true)
            KeyCode.VIEW_CHARACTERS -> setActiveKeyboardMode(KeyboardMode.CHARACTERS)
            KeyCode.VIEW_NUMERIC -> setActiveKeyboardMode(KeyboardMode.NUMERIC)
            KeyCode.VIEW_NUMERIC_ADVANCED -> setActiveKeyboardMode(KeyboardMode.NUMERIC_ADVANCED)
            KeyCode.VIEW_PHONE -> setActiveKeyboardMode(KeyboardMode.PHONE)
            KeyCode.VIEW_PHONE2 -> setActiveKeyboardMode(KeyboardMode.PHONE2)
            KeyCode.VIEW_SYMBOLS -> setActiveKeyboardMode(KeyboardMode.SYMBOLS)
            KeyCode.VIEW_SYMBOLS2 -> setActiveKeyboardMode(KeyboardMode.SYMBOLS2)
            KeyCode.UNDO -> activeEditorInstance.performUndo()
            else -> {
                when (activeState.keyboardMode) {
                    KeyboardMode.NUMERIC,
                    KeyboardMode.NUMERIC_ADVANCED,
                    KeyboardMode.PHONE,
                    KeyboardMode.PHONE2 -> when (data.type) {
                        KeyType.CHARACTER,
                        KeyType.NUMERIC -> {
                            val text = data.asString(isForDisplay = false)
                            if (isGlidePostEffect && (TextProcessor.isWord(text, subtypeManager.activeSubtype().primaryLocale) || text.isDigitsOnly())) {
                                activeEditorInstance.commitText(" ")
                            }
                            activeEditorInstance.commitText(text)
                        }
                        else -> when (data.code) {
                            KeyCode.PHONE_PAUSE,
                            KeyCode.PHONE_WAIT -> {
                                val text = data.asString(isForDisplay = false)
                                if (isGlidePostEffect && (TextProcessor.isWord(text, subtypeManager.activeSubtype().primaryLocale) || text.isDigitsOnly())) {
                                    activeEditorInstance.commitText(" ")
                                }
                                activeEditorInstance.commitText(text)
                            }
                        }
                    }
                    else -> when (data.type) {
                        KeyType.CHARACTER, KeyType.NUMERIC ->{
                            val text = data.asString(isForDisplay = false)
                            if (isGlidePostEffect && (TextProcessor.isWord(text, subtypeManager.activeSubtype().primaryLocale) || text.isDigitsOnly())) {
                                activeEditorInstance.commitText(" ")
                            }
                            activeEditorInstance.commitText(text)
                        }
                        else -> {
                            flogError(LogTopic.KEY_EVENTS) { "Received unknown key: $data" }
                        }
                    }
                }
            }
        }
        if (data.code != KeyCode.SHIFT && !activeState.capsLock && !inputEventDispatcher.isPressed(KeyCode.SHIFT)) {
            updateCapsState()
        }
        if (ev.data.code > KeyCode.SPACE) {
            isGlidePostEffect = false
        }
    }

    override fun onInputKeyRepeat(ev: InputKeyEvent) {
        florisboard.inputFeedbackController.keyRepeatedAction(ev.data)
        onInputKeyUp(ev)
    }

    override fun onInputKeyCancel(ev: InputKeyEvent) {
        val data = ev.data
        when (data.code) {
            KeyCode.SHIFT -> handleShiftCancel()
        }
    }

    fun handleGesture(word: String) {
        activeEditorInstance.commitGesture(fixCase(word))
    }

    /**
     * Changes a word to the current case.
     * eg if [KeyboardState.capsLock] is true, abc -> ABC
     *    if [caps]     is true, abc -> Abc
     *    otherwise            , abc -> abc
     */
    fun fixCase(word: String): String {
        return when {
            activeState.capsLock -> word.uppercase(subtypeManager.activeSubtype().primaryLocale.base)
            activeState.shiftLock -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(subtypeManager.activeSubtype().primaryLocale.base) else it.toString() }
            else -> word
        }
    }

    private fun KeyboardState.updateIsPrivate(force: Boolean = prefs.advanced.forcePrivateMode.get()) {
        this.isPrivateMode = force || this.imeOptions.flagNoPersonalizedLearning
    }
}
