package dev.kyoto.social;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;

/**
 * Simple Voice Chat plugin. Only SVC ever loads this class (fabric.mod.json "voicechat" entrypoint),
 * so Kyoto still works without SVC. We only borrow SVC's Opus encoder/decoder for friend calls.
 * (v6.2 used a reflection trick here that Fabric rejects - that crashed SVC at startup.)
 */
public class VoicePlugin implements VoicechatPlugin {
	@Override
	public String getPluginId() {
		return "kyoto";
	}

	@Override
	public void initialize(VoicechatApi api) {
		try {
			Calls.codecFactory = () -> {
				OpusEncoder enc = api.createEncoder();
				OpusDecoder dec = api.createDecoder();
				return new Calls.Codec() {
					@Override
					public byte[] encode(short[] pcm) {
						return enc.encode(pcm);
					}

					@Override
					public short[] decode(byte[] opus) {
						return dec.decode(opus);
					}

					@Override
					public void close() {
						enc.close();
						dec.close();
					}
				};
			};
		} catch (Throwable t) {
			// never take Simple Voice Chat down with us
			Calls.codecFactory = null;
		}
	}
}
