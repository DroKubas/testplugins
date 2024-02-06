package com.yfletch.ocbloods.action.travel.house;

import com.yfletch.ocbloods.OCBloodsContext;
import com.yfletch.occore.action.ItemAction;
import com.yfletch.occore.event.WrappedEvent;
import net.runelite.api.ItemID;

public class BreakHouseTab extends ItemAction<OCBloodsContext>
{
	public BreakHouseTab()
	{
		super("Break", "Teleport to house");
	}

	@Override
	public boolean isReady(OCBloodsContext ctx)
	{
		return ctx.isInBankingArea()
			&& !ctx.isBankOpen()
			&& ctx.getFreeInventorySlots() == 0;
	}

	@Override
	public boolean isWorking(OCBloodsContext ctx)
	{
		return ctx.flag("broke-tab");
	}

	@Override
	public boolean isDone(OCBloodsContext ctx)
	{
		return ctx.isInInstance();
	}

	@Override
	public void run(OCBloodsContext ctx, WrappedEvent event)
	{
		event.builder().item()
			.setOption("Break", 2)
			.setItem(ItemID.TELEPORT_TO_HOUSE)
			.override();
		ctx.flag("broke-tab", true, 1);
	}
}
