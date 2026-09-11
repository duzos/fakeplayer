package dev.duzo.players.network;

import commonnetwork.api.Network;
import dev.duzo.players.network.c2s.ApplyFakePlayerSkinPacketC2S;
import dev.duzo.players.network.c2s.BondPacketC2S;
import dev.duzo.players.network.c2s.ClearPatrolPacketC2S;
import dev.duzo.players.network.c2s.CustomBindStatePacketC2S;
import dev.duzo.players.network.c2s.CyclePosePacketC2S;
import dev.duzo.players.network.c2s.GiveAIMarkerPacketC2S;
import dev.duzo.players.network.c2s.LearnRecipePacketC2S;
import dev.duzo.players.network.c2s.OpenCrafterLearnPacketC2S;
import dev.duzo.players.network.c2s.OpenFakeMenuPacketC2S;
import dev.duzo.players.network.c2s.RequestSkinDataPacketC2S;
import dev.duzo.players.network.c2s.RequestItemPacketC2S;
import dev.duzo.players.network.c2s.RequestStockPacketC2S;
import dev.duzo.players.network.s2c.StockListPacketS2C;
import dev.duzo.players.network.c2s.SetAIFilterPacketC2S;
import dev.duzo.players.network.c2s.SetFakePlayerNamePacketC2S;
import dev.duzo.players.network.c2s.SetJobPacketC2S;
import dev.duzo.players.network.c2s.SetSkinKeyPacketC2S;
import dev.duzo.players.network.c2s.StartStopJobPacketC2S;
import dev.duzo.players.network.c2s.ToggleFakePlayerFlagPacketC2S;
import dev.duzo.players.network.c2s.UploadSkinPacketC2S;
import dev.duzo.players.network.s2c.OpenScreenPacketS2C;
import dev.duzo.players.network.s2c.SkinDataPacketS2C;

public class PlayersNetwork {
	public static void init() {
		Network.registerPacket(OpenScreenPacketS2C.TYPE, OpenScreenPacketS2C.CODEC, OpenScreenPacketS2C::handle);
		Network.registerPacket(SetSkinKeyPacketC2S.TYPE, SetSkinKeyPacketC2S.CODEC, SetSkinKeyPacketC2S::handle);
		Network.registerPacket(SetFakePlayerNamePacketC2S.TYPE, SetFakePlayerNamePacketC2S.CODEC, SetFakePlayerNamePacketC2S::handle);
		Network.registerPacket(ApplyFakePlayerSkinPacketC2S.TYPE, ApplyFakePlayerSkinPacketC2S.CODEC, ApplyFakePlayerSkinPacketC2S::handle);
		Network.registerPacket(CyclePosePacketC2S.TYPE, CyclePosePacketC2S.CODEC, CyclePosePacketC2S::handle);
		Network.registerPacket(ToggleFakePlayerFlagPacketC2S.TYPE, ToggleFakePlayerFlagPacketC2S.CODEC, ToggleFakePlayerFlagPacketC2S::handle);
		Network.registerPacket(UploadSkinPacketC2S.TYPE, UploadSkinPacketC2S.CODEC, UploadSkinPacketC2S::handle);
		Network.registerPacket(RequestSkinDataPacketC2S.TYPE, RequestSkinDataPacketC2S.CODEC, RequestSkinDataPacketC2S::handle);
		Network.registerPacket(SkinDataPacketS2C.TYPE, SkinDataPacketS2C.CODEC, SkinDataPacketS2C::handle);
		Network.registerPacket(BondPacketC2S.TYPE, BondPacketC2S.CODEC, BondPacketC2S::handle);
		Network.registerPacket(SetAIFilterPacketC2S.TYPE, SetAIFilterPacketC2S.CODEC, SetAIFilterPacketC2S::handle);
		Network.registerPacket(RequestItemPacketC2S.TYPE, RequestItemPacketC2S.CODEC, RequestItemPacketC2S::handle);
		Network.registerPacket(RequestStockPacketC2S.TYPE, RequestStockPacketC2S.CODEC, RequestStockPacketC2S::handle);
		Network.registerPacket(StockListPacketS2C.TYPE, StockListPacketS2C.CODEC, StockListPacketS2C::handle);
		Network.registerPacket(SetJobPacketC2S.TYPE, SetJobPacketC2S.CODEC, SetJobPacketC2S::handle);
		Network.registerPacket(StartStopJobPacketC2S.TYPE, StartStopJobPacketC2S.CODEC, StartStopJobPacketC2S::handle);
		Network.registerPacket(GiveAIMarkerPacketC2S.TYPE, GiveAIMarkerPacketC2S.CODEC, GiveAIMarkerPacketC2S::handle);
		Network.registerPacket(ClearPatrolPacketC2S.TYPE, ClearPatrolPacketC2S.CODEC, ClearPatrolPacketC2S::handle);
		Network.registerPacket(OpenCrafterLearnPacketC2S.TYPE, OpenCrafterLearnPacketC2S.CODEC, OpenCrafterLearnPacketC2S::handle);
		Network.registerPacket(LearnRecipePacketC2S.TYPE, LearnRecipePacketC2S.CODEC, LearnRecipePacketC2S::handle);
		Network.registerPacket(OpenFakeMenuPacketC2S.TYPE, OpenFakeMenuPacketC2S.CODEC, OpenFakeMenuPacketC2S::handle);
		Network.registerPacket(CustomBindStatePacketC2S.TYPE, CustomBindStatePacketC2S.CODEC, CustomBindStatePacketC2S::handle);
	}
}
