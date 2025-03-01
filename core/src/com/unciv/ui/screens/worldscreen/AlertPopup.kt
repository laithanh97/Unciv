package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.battle.Battle
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.LocationAction
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.civilization.diplomacy.RelationshipLevel
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.fillPlaceholders
import com.unciv.models.translations.tr
import com.unciv.ui.audio.MusicMood
import com.unciv.ui.audio.MusicTrackChooserFlags
import com.unciv.ui.components.KeyCharAndCode
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.keyShortcuts
import com.unciv.ui.components.extensions.onActivation
import com.unciv.ui.components.extensions.pad
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.diplomacyscreen.LeaderIntroTable
import com.unciv.ui.screens.victoryscreen.VictoryScreen
import java.util.EnumSet

/**
 * [Popup] communicating events other than trade offers to the player.
 * (e.g. First Contact, Wonder built, Tech researched,...)
 *
 * Called in [WorldScreen].update, which pulls them from viewingCiv.popupAlerts.
 *
 * @param worldScreen The parent screen
 * @param popupAlert The [PopupAlert] entry to present
 *
 * @see AlertType
 */
class AlertPopup(
    private val worldScreen: WorldScreen,
    private val popupAlert: PopupAlert
): Popup(worldScreen) {

    //region convenience getters
    private val music get() = UncivGame.Current.musicController
    private val gameInfo get() = worldScreen.gameInfo
    private val viewingCiv get() = worldScreen.viewingCiv
    private val stageWidth get() = worldScreen.stage.width
    private val stageHeight get() = worldScreen.stage.height
    private fun getCiv(civName: String) = gameInfo.getCivilization(civName)
    private fun getCity(cityId: String) = gameInfo.getCities().first { it.id == cityId }
    //endregion

    init {
        when (popupAlert.type) {
            AlertType.WarDeclaration -> addWarDeclaration()
            AlertType.Defeated -> addDefeated()
            AlertType.FirstContact -> addFirstContact()
            AlertType.CityConquered -> addCityConquered()
            AlertType.CityTraded -> addCityTraded()
            AlertType.BorderConflict -> addBorderConflict()
            AlertType.DemandToStopSettlingCitiesNear -> addDemandToStopSettlingCitiesNear()
            AlertType.CitySettledNearOtherCivDespiteOurPromise -> addCitySettledNearOtherCivDespiteOurPromise()
            AlertType.WonderBuilt -> addWonderBuilt()
            AlertType.TechResearched -> addTechResearched()
            AlertType.GoldenAge -> addGoldenAge()
            AlertType.DeclarationOfFriendship -> addDeclarationOfFriendship()
            AlertType.StartIntro -> addStartIntro()
            AlertType.DiplomaticMarriage -> addDiplomaticMarriage()
            AlertType.BulliedProtectedMinor, AlertType.AttackedProtectedMinor -> addBulliedOrAttackedProtectedMinor()
            AlertType.RecapturedCivilian -> addRecapturedCivilian()
            AlertType.GameHasBeenWon -> addGameHasBeenWon()
        }
    }

    //region AlertType handlers

    private fun addBorderConflict() {
        val civInfo = getCiv(popupAlert.value)
        addLeaderName(civInfo)
        addGoodSizedLabel("Remove your troops in our border immediately!").row()
        val responseTable = Table()
        responseTable.defaults().pad(0f, 5f)
        responseTable.add(getCloseButton("Sorry."))
        responseTable.add(getCloseButton("Never!"))
        add(responseTable)
    }

    private fun addBulliedOrAttackedProtectedMinor() {
        val involvedCivs = popupAlert.value.split('@')
        val bullyOrAttacker = getCiv(involvedCivs[0])
        val cityState = getCiv(involvedCivs[1])
        val player = viewingCiv
        addLeaderName(bullyOrAttacker)

        val isAtLeastNeutral = bullyOrAttacker.getDiplomacyManager(player).isRelationshipLevelGE(RelationshipLevel.Neutral)
        val text = when {
            popupAlert.type == AlertType.BulliedProtectedMinor && isAtLeastNeutral ->  // Nice message
                "I've been informed that my armies have taken tribute from [${cityState.civName}], a city-state under your protection.\nI assure you, this was quite unintentional, and I hope that this does not serve to drive us apart."
            popupAlert.type == AlertType.BulliedProtectedMinor ->  // Nasty message
                "We asked [${cityState.civName}] for a tribute recently and they gave in.\nYou promised to protect them from such things, but we both know you cannot back that up."
            isAtLeastNeutral ->  // Nice message
                "It's come to my attention that I may have attacked [${cityState.civName}], a city-state under your protection.\nWhile it was not my goal to be at odds with your empire, this was deemed a necessary course of action."
            else ->  // Nasty message
                "I thought you might like to know that I've launched an invasion of one of your little pet states.\nThe lands of [${cityState.civName}] will make a fine addition to my own."
        }
        addGoodSizedLabel(text).row()

        add(getCloseButton("You'll pay for this!", 'y') {
            player.getDiplomacyManager(bullyOrAttacker).sideWithCityState()
        }).row()
        add(getCloseButton("Very well.", 'n') {
            val capitalLocation = LocationAction(cityState.cities.asSequence().map { it.location }) // in practice 0 or 1 entries, that's OK
            player.addNotification("You have broken your Pledge to Protect [${cityState.civName}]!", capitalLocation, NotificationCategory.Diplomacy, cityState.civName)
            cityState.cityStateFunctions.removeProtectorCiv(player, forced = true)
        }).row()
    }

    private fun addCityConquered() {
        val city = getCity(popupAlert.value)
        addQuestionAboutTheCity(city.name)
        val conqueringCiv = gameInfo.getCurrentPlayerCivilization()

        if (city.foundingCiv != ""
                && city.civ.civName != city.foundingCiv // can't liberate if the city actually belongs to those guys
                && conqueringCiv.civName != city.foundingCiv) { // or belongs originally to us
            addLiberateOption(city.foundingCiv) {
                city.liberateCity(conqueringCiv)
                worldScreen.shouldUpdate = true
                close()
            }
            addSeparator()
        }

        if (conqueringCiv.isOneCityChallenger()) {
            addDestroyOption {
                city.puppetCity(conqueringCiv)
                city.destroyCity()
                worldScreen.shouldUpdate = true
                close()
            }
        } else {
            val mayAnnex = !conqueringCiv.hasUnique(UniqueType.MayNotAnnexCities)
            addAnnexOption(mayAnnex = mayAnnex) {
                city.puppetCity(conqueringCiv)
                city.annexCity()
                worldScreen.shouldUpdate = true
                close()
            }
            addSeparator()

            addPuppetOption(mayAnnex = mayAnnex) {
                city.puppetCity(conqueringCiv)
                worldScreen.shouldUpdate = true
                close()
            }
            addSeparator()

            addRazeOption(canRaze = city.canBeDestroyed(justCaptured = true), mayAnnex = mayAnnex) {
                city.puppetCity(conqueringCiv)
                if (mayAnnex) { city.annexCity() }
                city.isBeingRazed = true
                worldScreen.shouldUpdate = true
                close()
            }
        }
    }

    private fun addCitySettledNearOtherCivDespiteOurPromise() {
        val otherciv = getCiv(popupAlert.value)
        addLeaderName(otherciv)
        addGoodSizedLabel("We noticed your new city near our borders, despite your promise. This will have....implications.").row()
        add(getCloseButton("Very well."))
    }

    private fun addCityTraded() {
        val city = getCity(popupAlert.value)
        addQuestionAboutTheCity(city.name)
        val conqueringCiv = gameInfo.getCurrentPlayerCivilization()

        if (!conqueringCiv.isAtWarWith(getCiv(city.foundingCiv))) {
            addLiberateOption(city.foundingCiv) {
                city.liberateCity(conqueringCiv)
                worldScreen.shouldUpdate = true
                close()
            }
            addSeparator()
        }
        add(getCloseButton("Keep it")).row()
    }

    private fun addDeclarationOfFriendship() {
        val otherciv = getCiv(popupAlert.value)
        val playerDiploManager = viewingCiv.getDiplomacyManager(otherciv)
        addLeaderName(otherciv)
        addGoodSizedLabel("My friend, shall we declare our friendship to the world?").row()
        add(getCloseButton("We are not interested.", 'n')).row()
        add(getCloseButton("Declare Friendship ([30] turns)", 'y') {
            playerDiploManager.signDeclarationOfFriendship()
        }).row()
    }

    private fun addDefeated() {
        val civInfo = getCiv(popupAlert.value)
        addLeaderName(civInfo)
        addGoodSizedLabel(civInfo.nation.defeated).row()
        add(getCloseButton("Farewell."))
        music.chooseTrack(civInfo.civName, MusicMood.Defeat, EnumSet.of(MusicTrackChooserFlags.SuffixMustMatch))
    }

    private fun addDemandToStopSettlingCitiesNear() {
        val otherciv = getCiv(popupAlert.value)
        val playerDiploManager = viewingCiv.getDiplomacyManager(otherciv)
        addLeaderName(otherciv)
        addGoodSizedLabel("Please don't settle new cities near us.").row()
        add(getCloseButton("Very well, we shall look for new lands to settle.", 'y') {
            playerDiploManager.agreeNotToSettleNear()
        }).row()
        add(getCloseButton("We shall do as we please.", 'n') {
            playerDiploManager.refuseDemandNotToSettleNear()
        }).row()
    }

    private fun addDiplomaticMarriage() {
        val city = getCity(popupAlert.value)
        addGoodSizedLabel(city.name.tr() + ": " + "What would you like to do with the city?".tr(), Constants.headingFontSize) // Add name because there might be several cities
            .padBottom(20f).row()
        val marryingCiv = gameInfo.getCurrentPlayerCivilization()

        if (marryingCiv.isOneCityChallenger()) {
            addDestroyOption {
                city.destroyCity(overrideSafeties = true)
                worldScreen.shouldUpdate = true
                close()
            }
        } else {
            val mayAnnex = !marryingCiv.hasUnique(UniqueType.MayNotAnnexCities)
            addAnnexOption(mayAnnex) {
                city.annexCity()
                close()
            }
            addSeparator()

            addPuppetOption(mayAnnex) {
                city.isPuppet = true
                city.cityStats.update()
                worldScreen.shouldUpdate = true
                close()
            }
        }
    }

    private fun addFirstContact() {
        val civInfo = getCiv(popupAlert.value)
        val nation = civInfo.nation
        addLeaderName(civInfo)
        music.chooseTrack(civInfo.civName, MusicMood.themeOrPeace, MusicTrackChooserFlags.setSpecific)
        if (civInfo.isCityState()) {
            addGoodSizedLabel("We have encountered the City-State of [${nation.name}]!").row()
            add(getCloseButton("Excellent!"))
        } else {
            addGoodSizedLabel(nation.introduction).row()
            add(getCloseButton("A pleasure to meet you."))
        }
    }

    private fun addGameHasBeenWon() {
        val victoryData = gameInfo.victoryData!!
        addGoodSizedLabel("[${victoryData.winningCiv}] has won a [${victoryData.victoryType}] Victory!").row()
        addButton("Victory status"){ close(); worldScreen.game.pushScreen(VictoryScreen(worldScreen)) }.row()
        add(getCloseButton(Constants.close))
    }

    private fun addGoldenAge() {
        addGoodSizedLabel("GOLDEN AGE")
        addSeparator()
        addGoodSizedLabel("Your citizens have been happy with your rule for so long that the empire enters a Golden Age!").row()
        add(getCloseButton(Constants.close))
        music.chooseTrack(viewingCiv.civName, MusicMood.Golden, MusicTrackChooserFlags.setSpecific)
    }

    private fun addRecapturedCivilian() {
        val position = Vector2().fromString(popupAlert.value)
        val tile = gameInfo.tileMap[position]
        val capturedUnit = tile.civilianUnit // This has got to be it
        if (capturedUnit == null) { // the unit disappeared somehow? maybe a modded action?
            close()
            return
        }
        val originalOwner = getCiv(capturedUnit.originalOwner!!)
        val captor = viewingCiv

        addGoodSizedLabel("Return [${capturedUnit.name}] to [${originalOwner.civName}]?")
        addSeparator()
        addGoodSizedLabel("The [${capturedUnit.name}] we liberated originally belonged to [${originalOwner.civName}]. They will be grateful if we return it to them.").row()
        val responseTable = Table()
        responseTable.defaults()
            .pad(0f, 30f) // Small buttons, plenty of pad so we don't fat-finger it
        responseTable.add(getCloseButton(Constants.yes, 'y') {
            // Return it to original owner
            val unitName = capturedUnit.baseUnit.name
            capturedUnit.destroy()
            val closestCity =
                    originalOwner.cities.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) }
            if (closestCity != null) {
                // Attempt to place the unit near their nearest city
                originalOwner.units.placeUnitNearTile(closestCity.location, unitName)
            }

            if (originalOwner.isCityState()) {
                originalOwner.getDiplomacyManager(captor).addInfluence(45f)
            } else if (originalOwner.isMajorCiv()) {
                // No extra bonus from doing it several times
                originalOwner.getDiplomacyManager(captor)
                    .setModifier(DiplomaticModifiers.ReturnedCapturedUnits, 20f)
            }
        })
        responseTable.add(getCloseButton(Constants.no, 'n') {
            // Take it for ourselves
            Battle.captureOrConvertToWorker(capturedUnit, captor)
        }).row()
        add(responseTable)
    }

    private fun addStartIntro() {
        val civInfo = viewingCiv
        addLeaderName(civInfo)
        addGoodSizedLabel(civInfo.nation.startIntroPart1).row()
        addGoodSizedLabel(civInfo.nation.startIntroPart2).row()
        add(getCloseButton("Let's begin!"))
    }

    private fun addTechResearched() {
        val tech = gameInfo.ruleset.technologies[popupAlert.value]!!
        addGoodSizedLabel(tech.name)
        addSeparator()
        val centerTable = Table()
        centerTable.add(tech.quote.toLabel().apply { wrap = true }).width(stageWidth / 3)
        centerTable.add(ImageGetter.getTechIconPortrait(tech.name, 100f)).pad(20f)
        val descriptionScroll = ScrollPane(tech.getDescription(viewingCiv).toLabel().apply { wrap = true })
        centerTable.add(descriptionScroll).width(stageWidth / 3).maxHeight(stageHeight / 2)
        add(centerTable).row()
        add(getCloseButton(Constants.close))
        music.chooseTrack(tech.name, MusicMood.Researched, MusicTrackChooserFlags.setSpecific)
    }

    private fun addWarDeclaration() {
        val civInfo = getCiv(popupAlert.value)
        addLeaderName(civInfo)
        addGoodSizedLabel(civInfo.nation.declaringWar).row()
        val responseTable = Table()
        responseTable.defaults().pad(0f, 5f)
        responseTable.add(getCloseButton("You'll pay for this!"))
        responseTable.add(getCloseButton("Very well."))
        add(responseTable)
        music.chooseTrack(civInfo.civName, MusicMood.War, MusicTrackChooserFlags.setSpecific)
    }

    private fun addWonderBuilt() {
        val wonder = gameInfo.ruleset.buildings[popupAlert.value]!!
        addGoodSizedLabel(wonder.name)
        addSeparator()
        if(ImageGetter.wonderImageExists(wonder.name)) {    // Wonder Graphic exists
            if(stageHeight * 3 > stageWidth * 4) {    // Portrait
                add(ImageGetter.getWonderImage(wonder.name))
                    .width(stageWidth / 1.5f)
                    .height(stageWidth / 3)
                    .row()
            }
            else {  // Landscape (or squareish)
                add(ImageGetter.getWonderImage(wonder.name))
                    .width(stageWidth / 2.5f)
                    .height(stageWidth / 5)
                    .row()
            }
        } else {    // Fallback
            add(ImageGetter.getConstructionPortrait(wonder.name, 100f)).pad(20f).row()
        }

        val centerTable = Table()
        centerTable.add(wonder.quote.toLabel().apply { wrap = true }).width(stageWidth / 3).pad(10f)
        centerTable.add(wonder.getShortDescription()
            .toLabel().apply { wrap = true }).width(stageWidth / 3).pad(10f)
        add(centerTable).row()
        add(getCloseButton(Constants.close))
        music.chooseTrack(wonder.name, MusicMood.Wonder, MusicTrackChooserFlags.setSpecific)
    }

    //endregion
    //region Helpers

    private fun getCloseButton(text: String, key: Char = Char.MIN_VALUE, action: (() -> Unit)? = null): TextButton {
        // Popup.addCloseButton is close but AlertPopup needs the flexibility to add these inside a wrapper
        val button = text.toTextButton()
        button.onActivation {
            if (action != null) action()
            worldScreen.shouldUpdate = true
            close()
        }
        if (key == Char.MIN_VALUE) {
            button.keyShortcuts.add(KeyCharAndCode.BACK)
            button.keyShortcuts.add(KeyCharAndCode.SPACE)
        } else {
            button.keyShortcuts.add(key)
        }
        return button
    }

    private fun addLeaderName(civInfo: Civilization) {
        add(LeaderIntroTable(civInfo))
        addSeparator()
    }

    private fun addQuestionAboutTheCity(cityName: String) {
        addGoodSizedLabel("What would you like to do with the city of [$cityName]?",
            Constants.headingFontSize).padBottom(20f).row()
    }

    private fun addDestroyOption(destroyAction: () -> Unit) {
        val button = "Destroy".toTextButton()
        button.onActivation { destroyAction() }
        button.keyShortcuts.add('d')
        add(button).row()
        addGoodSizedLabel("Destroying the city instantly razes the city to the ground.").row()
    }

    private fun addAnnexOption(mayAnnex: Boolean, annexAction: () -> Unit) {
        val button = "Annex".toTextButton()
        button.apply {
            if (!mayAnnex) disable() else {
                button.onActivation { annexAction() }
                button.keyShortcuts.add('a')
            }
        }
        add(button).row()
        if (mayAnnex) {
            addGoodSizedLabel("Annexed cities become part of your regular empire.").row()
            addGoodSizedLabel("Their citizens generate 2x the unhappiness, unless you build a courthouse.").row()
        } else {
            addGoodSizedLabel("Your civilization may not annex this city.").row()
        }

    }

    private fun addPuppetOption(mayAnnex: Boolean, puppetAction: () -> Unit) {
        val button = "Puppet".toTextButton()
        button.onActivation { puppetAction() }
        button.keyShortcuts.add('p')
        add(button).row()
        addGoodSizedLabel("Puppeted cities do not increase your tech or policy cost.").row()
        addGoodSizedLabel("You have no control over the the production of puppeted cities.").row()
        addGoodSizedLabel("Puppeted cities also generate 25% less Gold and Science.").row()
        if (mayAnnex) addGoodSizedLabel("A puppeted city can be annexed at any time.").row()
    }

    private fun addLiberateOption(foundingCiv: String, liberateAction: () -> Unit) {
        val button = "Liberate (city returns to [originalOwner])".fillPlaceholders(foundingCiv).toTextButton()
        button.onActivation { liberateAction() }
        button.keyShortcuts.add('l')
        add(button).row()
        addGoodSizedLabel("Liberating a city returns it to its original owner, giving you a massive relationship boost with them!")
    }

    private fun addRazeOption(canRaze: Boolean, mayAnnex: Boolean, razeAction: () -> Unit) {
        val button = "Raze".toTextButton()
        button.apply {
            if (!canRaze) disable()
            else {
                onActivation { razeAction() }
                keyShortcuts.add('r')
            }
        }
        add(button).row()
        if (canRaze) {
            if (mayAnnex) {
                addGoodSizedLabel("Razing the city annexes it, and starts burning the city to the ground.").row()
            } else {
                addGoodSizedLabel("Razing the city puppets it, and starts burning the city to the ground.").row()
            }
            addGoodSizedLabel("The population will gradually dwindle until the city is destroyed.").row()
        } else {
            addGoodSizedLabel("Original capitals and holy cities cannot be razed.").row()
        }
    }

    //endregion

    override fun close() {
        viewingCiv.popupAlerts.remove(popupAlert)
        super.close()
    }
}
