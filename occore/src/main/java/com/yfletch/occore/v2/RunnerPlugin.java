package com.yfletch.occore.v2;

import com.google.inject.Inject;
import com.yfletch.occore.v2.interaction.DeferredInteraction;
import com.yfletch.occore.v2.interaction.Entities;
import com.yfletch.occore.v2.overlay.BankItemDebugOverlay;
import com.yfletch.occore.v2.overlay.CoreDebugOverlay;
import com.yfletch.occore.v2.overlay.CoreStatisticsOverlay;
import com.yfletch.occore.v2.overlay.EquipmentItemDebugOverlay;
import com.yfletch.occore.v2.overlay.InteractionOverlay;
import com.yfletch.occore.v2.overlay.InventoryItemDebugOverlay;
import com.yfletch.occore.v2.overlay.WorldDebug;
import com.yfletch.occore.v2.overlay.WorldDebugOverlay;
import com.yfletch.occore.v2.rule.DynamicRule;
import com.yfletch.occore.v2.rule.RequirementRule;
import com.yfletch.occore.v2.rule.Rule;
import com.yfletch.occore.v2.util.RunnerUtil;
import com.yfletch.occore.v2.util.TextColor;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.PostMenuSort;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.OverlayMenuClicked;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.HotkeyListener;
import net.unethicalite.api.commons.Rand;
import net.unethicalite.client.Static;

@Slf4j
public abstract class RunnerPlugin<TContext extends CoreContext> extends Plugin
{
	private static final Rule<?> BREAK_RULE = new DynamicRule<>()
		.name("Break").noop()
		.message(TextColor.SPELL + "Taking a break");

	@Inject private ConfigManager configManager;
	@Inject private KeyManager keyManager;
	@Inject private OverlayManager overlayManager;

	@Inject private AutoClick autoClick;
	@Getter
	@Inject private BreakHandler breakHandler;
	@Inject private Client client;

	@Getter
	private final List<Rule<TContext>> rules = new LinkedList<>();

	private List<Rule<TContext>> groupRules;

	@Getter
	private Rule<TContext> currentRule = null;

	@Getter
	private DeferredInteraction nextInteraction = null;

	@Getter
	private boolean isDelaying = false;

	@Getter
	private List<String> messages = null;

	private int actionsThisTick = 0;

	protected StatisticTracker statistics;
	private InteractionOverlay interactionOverlay;
	private CoreStatisticsOverlay statisticsOverlay;
	private CoreDebugOverlay debugOverlay;
	@Inject private WorldDebugOverlay worldDebugOverlay;
	@Inject private BankItemDebugOverlay bankItemDebugOverlay;
	@Inject private InventoryItemDebugOverlay inventoryItemDebugOverlay;
	@Inject private EquipmentItemDebugOverlay equipmentItemDebugOverlay;

	private TContext context;

	@Setter
	private CoreConfig config;

	@Setter
	private String configGroup;

	@Setter
	@Accessors(fluent = true)
	private boolean processOnGameTick = true;

	@Setter
	@Accessors(fluent = true)
	private boolean processOnMouseClick = true;

	/**
	 * Maximum amount of actions to execute in a single
	 * tick.
	 */
	@Setter
	@Accessors(fluent = true)
	private int actionsPerTick = 1;

	@Setter
	@Accessors(fluent = true)
	private boolean refreshOnConfigChange = false;

	private static final Executor RESOLUTION_EXECUTOR = Executors.newSingleThreadExecutor();

	private final HotkeyListener hotkeyListener = new HotkeyListener(() -> config.quickToggleKeybind())
	{
		@Override
		public void hotkeyPressed()
		{
			configManager.setConfiguration(configGroup, "enabled", !enabled());
		}
	};

	public boolean enabled()
	{
		return config.enabled();
	}

	public int getRuleRepeatsLeft()
	{
		return currentRule == null ? 1 : currentRule.repeatsLeft();
	}

	public void setContext(TContext context)
	{
		this.context = context;
		context.setPlugin(this);
	}

	/**
	 * Clear all current rules and run setup again
	 */
	public void refresh()
	{
		rules.clear();
		setup();
	}

	public boolean areBreaksEnabled()
	{
		return config.enableBreaks();
	}

	public boolean isInBreak()
	{
		return breakHandler.isInBreak();
	}

	public String getTimeToNextBreak()
	{
		return breakHandler.getTimeToNextBreak();
	}

	public String getTimeRemainingInBreak()
	{
		return breakHandler.getTimeRemainingInBreak();
	}

	/**
	 * Setup this plugin by adding rules here. As this is executed on
	 * the client thread, it is possible to query items/objects etc.
	 */
	public abstract void setup();

	protected final void add(Rule<TContext> rule)
	{
		Objects.requireNonNullElse(groupRules, rules).add(rule);
	}

	/**
	 * Create, add and return a new rule instance that can be customised similar to a builder
	 */
	protected final DynamicRule<TContext> action()
	{
		final var rule = new DynamicRule<TContext>();
		add(rule);
		return rule;
	}

	/**
	 * Create, add and return a new requirement rule instance that can be customised similar to a builder
	 */
	protected final RequirementRule<TContext> requirements()
	{
		final var rule = new RequirementRule<TContext>();
		add(rule);
		return rule.name("Requirements");
	}

	protected final void group(Predicate<TContext> when, Runnable factory)
	{
		groupRules = new ArrayList<>();
		factory.run();
		groupRules.forEach(
			rule -> rule.when(rule.when() != null ? rule.when().and(when) : when)
		);
		rules.addAll(groupRules);
		groupRules = null;
	}

	private void updateDelay()
	{
		context.tickDelays();

		if (context.getMinDelayTimer() > 0)
		{
			return;
		}

		var delay = 0;
		if (context.getDelayTimer() > 0)
		{
			// [0, 2)
			// the lower the interaction delay is, the more likely
			// it will execute. 4t => 1/4, 3t => 1/3, etc
			delay = Rand.nextInt(0, context.getDelayTimer() + 1);
		}

		if (delay == 0)
		{
			isDelaying = false;

			// just for debugging
			context.setDelayTimer(0);
		}
	}

	private boolean canExecute()
	{
		if (!enabled() || currentRule == null || !currentRule.canExecute())
		{
			return false;
		}

		return !isDelaying;
	}

	private void executeWithDeviousAPI()
	{
		if (canExecute() && config.pluginApi() == PluginAPI.DEVIOUS)
		{
			final var interaction = updateInteraction(currentRule);
			if (interaction != null && actionsThisTick < actionsPerTick)
			{
				interaction.execute();
				actionsThisTick++;
				currentRule.callback(context);
				currentRule.useRepeat();

				if (!currentRule.canExecute())
				{
					currentRule.completeCallback(context);
				}

				// in case there's something else we should do straight
				// away, process and execute again
				RESOLUTION_EXECUTOR.execute(this::resolveRules);
				executeWithDeviousAPI();
			}
		}
	}

	@Nullable
	private DeferredInteraction updateInteraction(Rule<TContext> rule)
	{
		nextInteraction = rule.run(context);
		if (nextInteraction == null || rule.isNoop())
		{
			// fallback to rule message
			messages = rule.messages(context);
			if (messages == null)
			{
				final var ruleName = rule.name() != null
					? "\"" + rule.name() + "\""
					: "unknown rule";

				messages = List.of(
					TextColor.WHITE + "Nothing to do (no interaction)",
					TextColor.GRAY + "For " + ruleName
				);
			}
		}
		else
		{
			messages = null;
			nextInteraction.onActive();
		}

		return nextInteraction;
	}

	private boolean passes(Rule<TContext> rule)
	{
		return rule.passes(context) && !rule.continues(context);
	}

	private void enable(Rule<TContext> rule)
	{
		// reset previous rule
		// if the previous rule should only be reset on tick,
		// then skip it
		if (currentRule != null
			&& !(currentRule instanceof DynamicRule && ((DynamicRule<TContext>) currentRule).resetsOnTick()))
		{
			currentRule.reset(context);
		}

		// reset rule status
		rule.reset(context);

		// update interaction display
		updateInteraction(rule);

		final var maxDelay = rule.getMaxDelay(context);
		final var minDelay = rule.getMinDelay(context);

		// use new max delay
		context.setDelayTimer(maxDelay);
		context.setMinDelayTimer(minDelay);
		// reset delay status
		isDelaying = maxDelay > 0 || minDelay > 0;

		currentRule = rule;
	}

	/**
	 * Determine the next rule to move to
	 */
	@SuppressWarnings("unchecked")
	private void resolveRules()
	{
		if (breakHandler.isInBreak())
		{
			if (currentRule != BREAK_RULE)
			{
				enable((Rule<TContext>) BREAK_RULE);
			}
			return;
		}

		final var startResolution = Instant.now();
		// find new rule to apply
		for (final var rule : rules)
		{
			final var startRuleCheck = Instant.now();
			final var pass = passes(rule);
			final var ruleTime = Duration.between(startRuleCheck, Instant.now()).toMillis();

			if (ruleTime >= 10)
			{
				log.info("Slow rule - \"{}\" took {}ms!", rule.name(), ruleTime);
			}

			if (pass)
			{
				if (rule == currentRule)
				{
					// current rule is still active -
					// continue to below
					break;
				}

				enable(rule);
				break;
			}
		}

		if (currentRule != null)
		{
			// clear rule if it no longer passes
			if (!passes(currentRule))
			{
				currentRule.reset(context);
				currentRule = null;
				nextInteraction = null;
				messages = null;
			}

			if (currentRule != null)
			{
				updateInteraction(currentRule);
			}
		}

		final var resolutionTime = Duration.between(startResolution, Instant.now()).toMillis();

		if (resolutionTime >= 40)
		{
			log.info("Rule resolution took {}ms!", resolutionTime);
		}
	}

	private void createOverlays()
	{
		interactionOverlay = new InteractionOverlay(this);
		debugOverlay = new CoreDebugOverlay(this, context);

		statistics = new StatisticTracker();
		statisticsOverlay = new CoreStatisticsOverlay(this, statistics);

		WorldDebug.setWorldOverlay(worldDebugOverlay);
		WorldDebug.setBankItemDebugOverlay(bankItemDebugOverlay);
		WorldDebug.setInventoryItemDebugOverlay(inventoryItemDebugOverlay);
		WorldDebug.setEquipmentItemDebugOverlay(equipmentItemDebugOverlay);
	}

	@Override
	protected void startUp()
	{
		createOverlays();

		if (config.showActionOverlay())
		{
			overlayManager.add(interactionOverlay);
		}

		if (config.showStatisticsOverlay())
		{
			overlayManager.add(statisticsOverlay);
		}

		if (config.showDebugOverlay())
		{
			overlayManager.add(debugOverlay);
		}

		if (config.showWorldDebugOverlay())
		{
			overlayManager.add(worldDebugOverlay);
			overlayManager.add(bankItemDebugOverlay);
			overlayManager.add(inventoryItemDebugOverlay);
			overlayManager.add(equipmentItemDebugOverlay);
		}

		if (config.quickToggleKeybind() != null)
		{
			keyManager.registerKeyListener(hotkeyListener);
		}

		autoClick.setClicksPerTick(config.clicksPerTick());
		breakHandler.setInterval(config.breakInterval());
		breakHandler.setDuration(config.breakDuration());

		Static.getClientThread().invokeLater(this::setup);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(interactionOverlay);
		overlayManager.remove(statisticsOverlay);
		overlayManager.remove(debugOverlay);
		overlayManager.remove(worldDebugOverlay);
		overlayManager.remove(bankItemDebugOverlay);
		overlayManager.remove(inventoryItemDebugOverlay);
		overlayManager.remove(equipmentItemDebugOverlay);
		keyManager.unregisterKeyListener(hotkeyListener);
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		context.tick(true);
		Entities.clearInteracted();
		actionsThisTick = 0;

		// reset all "once-per-tick" rules
		for (final var rule : rules)
		{
			if (rule instanceof DynamicRule && ((DynamicRule<TContext>) rule).resetsOnTick())
			{
				rule.reset(context);
			}
		}

		if (processOnGameTick)
		{
			RESOLUTION_EXECUTOR.execute(() -> {
				this.resolveRules();

				if (
					config.enabled()
						&& config.pluginApi() == PluginAPI.ONE_CLICK_AUTO
						&& canExecute()
				)
				{
					if (!autoClick.ready())
					{
						configManager.setConfiguration(
							configGroup,
							"enabled",
							false
						);
					}
					else
					{
						autoClick.run();
					}
				}
			});
		}

		executeWithDeviousAPI();
		updateDelay();

		if (config.enabled() && !breakHandler.isInBreak())
		{
			statistics.tick();
		}

		if (config.enableBreaks())
		{
			breakHandler.tick();
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (config.debugRawMenuEntries())
		{
			RunnerUtil.log("raw", event.getMenuEntry());
		}

		context.tick(false);

		if (enabled() && config.pluginApi() == PluginAPI.ONE_CLICK_CONSUME && !canExecute())
		{
			event.consume();
			if (config.debugOCMenuEntries())
			{
				RunnerUtil.log("OC", "Consumed");
			}
		}

		if (canExecute() && config.pluginApi().isOneClick()
			&& !event.getMenuOption().startsWith("* ")
			&& nextInteraction != null)
		{
			RunnerUtil.log(
				"OC",
				TextColor.DANGER + "Failed to override menu click."
			);
			RunnerUtil.chat(
				"OC",
				TextColor.DANGER + "Please make sure the mouse cursor" +
					" is not over an item slot, or"
			);
			RunnerUtil.chat(
				"OC",
				TextColor.DANGER + "another plugin's interface."
			);
		}

		if (currentRule != null && event.getMenuOption().startsWith("* "))
		{
			if (nextInteraction != null)
			{
				nextInteraction.prepare();
			}

			actionsThisTick++;
			currentRule.callback(context);
			currentRule.useRepeat();

			if (!currentRule.canExecute())
			{
				currentRule.completeCallback(context);
			}

			if (config.debugOCMenuEntries())
			{
				RunnerUtil.log("OC", event.getMenuEntry());
			}
		}

		if (processOnMouseClick)
		{
			RESOLUTION_EXECUTOR.execute(this::resolveRules);
		}

		executeWithDeviousAPI();
	}

	@Subscribe
	public void onPostMenuSort(PostMenuSort event)
	{
		if (config.pluginApi().isOneClick() && canExecute())
		{
			if (nextInteraction != null)
			{
				// add the one-click entry to the top
				final var entry = nextInteraction.createMenuEntry();
				entry.setOption("* " + entry.getOption());
				entry.setForceLeftClick(true);
			}
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (event.getGroup().equals(configGroup))
		{
			if (refreshOnConfigChange)
			{
				Static.getClientThread().invokeLater(this::refresh);
			}

			switch (event.getKey())
			{
				case "enabled":
					autoClick.setPoint(client.getMouseCanvasPosition());
					break;
				case "clicksPerTick":
					autoClick.setClicksPerTick(config.clicksPerTick());
					break;
				case "showActionOverlay":
					if (event.getNewValue().equals("true"))
					{
						overlayManager.add(interactionOverlay);
					}
					else
					{
						overlayManager.remove(interactionOverlay);
					}
					break;
				case "showStatisticsOverlay":
					if (event.getNewValue().equals("true"))
					{
						overlayManager.add(statisticsOverlay);
					}
					else
					{
						overlayManager.remove(statisticsOverlay);
					}
					break;
				case "showDebugOverlay":
					if (event.getNewValue().equals("true"))
					{
						overlayManager.add(debugOverlay);
					}
					else
					{
						overlayManager.remove(debugOverlay);
					}
					break;
				case "showWorldDebugOverlay":
					if (event.getNewValue().equals("true"))
					{
						overlayManager.add(worldDebugOverlay);
						overlayManager.add(bankItemDebugOverlay);
						overlayManager.add(inventoryItemDebugOverlay);
						overlayManager.add(equipmentItemDebugOverlay);
					}
					else
					{
						overlayManager.remove(worldDebugOverlay);
						overlayManager.remove(bankItemDebugOverlay);
						overlayManager.remove(inventoryItemDebugOverlay);
						overlayManager.remove(equipmentItemDebugOverlay);
					}
					break;
				case "enableBreaks":
				case "breakInterval":
				case "breakDuration":
					breakHandler.setDuration(config.breakDuration());
					breakHandler.setInterval(config.breakInterval());
					breakHandler.reset();
			}
		}
	}

	@Subscribe
	public void onOverlayMenuClicked(OverlayMenuClicked event)
	{
		if (event.getOverlay() == statisticsOverlay
			&& event.getEntry().getOption().equals(CoreStatisticsOverlay.CLEAR_STATISTICS))
		{
			statistics.clear();
		}
	}
}
