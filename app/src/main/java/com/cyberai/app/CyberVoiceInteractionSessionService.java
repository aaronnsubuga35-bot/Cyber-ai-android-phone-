
package com.cyberai.app;

import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;
import android.os.Bundle;

public class CyberVoiceInteractionSessionService extends VoiceInteractionSessionService {
    @Override
    public VoiceInteractionSession onNewSession(Bundle args) {
        return new CyberVoiceInteractionSession(this);
    }
}
