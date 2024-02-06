package com.yfletch.ocbloods.action.travel.caves;

import com.yfletch.ocbloods.OCBloodsContext;
import com.yfletch.occore.action.ObjectAction;
import com.yfletch.occore.event.WrappedEvent;

public class EnterCave3 extends ObjectAction<OCBloodsContext>
{
	public EnterCave3()
	{
		super("Enter", "Cave");
	}

	@Override
	public boolean isReady(OCBloodsContext ctx)
	{
		return ctx.isInTunnel3();
	}

	@Override
	public boolean isDone(OCBloodsContext ctx)
	{
		return !ctx.isInTunnel3();
	}

	@Override
	public void run(OCBloodsContext ctx, WrappedEvent event)
	{
		event.builder().object()
			.setOption("Enter", 1)
			.setObject(43759)
			.override();
	}
}
