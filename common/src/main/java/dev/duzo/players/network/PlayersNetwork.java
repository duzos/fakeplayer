package dev.duzo.players.network;

import commonnetwork.api.Network;
import dev.duzo.players.network.c2s.ApplyFakePlayerSkinPacketC2S;
import dev.duzo.players.network.c2s.CyclePosePacketC2S;
import dev.duzo.players.network.c2s.RequestSkinDataPacketC2S;
import dev.duzo.players.network.c2s.SetFakePlayerNamePacketC2S;
import dev.duzo.players.network.c2s.SetSkinKeyPacketC2S;
import dev.duzo.players.network.c2s.ToggleFakePlayerFlagPacketC2S;
import dev.duzo.players.network.c2s.UploadSkinPacketC2S;
import dev.duzo.players.network.s2c.OpenScreenPacketS2C;
import dev.duzo.players.network.s2c.SkinDataPacketS2C;

public class PlayersNetwork {
	public static void init() {
		Network.registerPacket(OpenScreenPacketS2C.LOCATION, OpenScreenPacketS2C.class, OpenScreenPacketS2C::encode, OpenScreenPacketS2C::decode, OpenScreenPacketS2C::handle);
		Network.registerPacket(SetSkinKeyPacketC2S.LOCATION, SetSkinKeyPacketC2S.class, SetSkinKeyPacketC2S::encode, SetSkinKeyPacketC2S::decode, SetSkinKeyPacketC2S::handle);
		Network.registerPacket(SetFakePlayerNamePacketC2S.LOCATION, SetFakePlayerNamePacketC2S.class, SetFakePlayerNamePacketC2S::encode, SetFakePlayerNamePacketC2S::decode, SetFakePlayerNamePacketC2S::handle);
		Network.registerPacket(ApplyFakePlayerSkinPacketC2S.LOCATION, ApplyFakePlayerSkinPacketC2S.class, ApplyFakePlayerSkinPacketC2S::encode, ApplyFakePlayerSkinPacketC2S::decode, ApplyFakePlayerSkinPacketC2S::handle);
		Network.registerPacket(CyclePosePacketC2S.LOCATION, CyclePosePacketC2S.class, CyclePosePacketC2S::encode, CyclePosePacketC2S::decode, CyclePosePacketC2S::handle);
		Network.registerPacket(ToggleFakePlayerFlagPacketC2S.LOCATION, ToggleFakePlayerFlagPacketC2S.class, ToggleFakePlayerFlagPacketC2S::encode, ToggleFakePlayerFlagPacketC2S::decode, ToggleFakePlayerFlagPacketC2S::handle);
		Network.registerPacket(UploadSkinPacketC2S.LOCATION, UploadSkinPacketC2S.class, UploadSkinPacketC2S::encode, UploadSkinPacketC2S::decode, UploadSkinPacketC2S::handle);
		Network.registerPacket(RequestSkinDataPacketC2S.LOCATION, RequestSkinDataPacketC2S.class, RequestSkinDataPacketC2S::encode, RequestSkinDataPacketC2S::decode, RequestSkinDataPacketC2S::handle);
		Network.registerPacket(SkinDataPacketS2C.LOCATION, SkinDataPacketS2C.class, SkinDataPacketS2C::encode, SkinDataPacketS2C::decode, SkinDataPacketS2C::handle);
	}
}
