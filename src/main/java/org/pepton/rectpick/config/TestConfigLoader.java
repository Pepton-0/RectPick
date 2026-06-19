package org.pepton.rectpick.config;

import org.lwjgl.glfw.GLFW;

/**
 * Temporary hard-coded config loader used until a real config file is introduced.
 */
public final class TestConfigLoader implements IConfigLoader {
    @Override
    public String getPickKeyTranslationKey() {
        return "key.rectpick.pick";
    }

    @Override
    public String getKeyCategoryTranslationKey() {
        return "key.categories.rectpick";
    }

    @Override
    public int getDefaultPickKey() {
        return GLFW.GLFW_KEY_LEFT_ALT;
    }

    @Override
    public double getMoveOperationMaxDragDistance() {
        return 4.0;
    }

    @Override
    public int getSelectionOutlineColor() {
        return 0xCC3399FF;
    }

    @Override
    public int getSelectedSlotFillColor() {
        return 0x993399FF;
    }

    @Override
    public int getMovedSlotFillColor() {
        return 0xAA66CCFF;
    }

    @Override
    public double getSelectedSlotFadeInSeconds() {
        return 0.25;
    }

    @Override
    public double getMovedSlotHoldSeconds() {
        return 1.5;
    }

    @Override
    public double getMovedSlotFadeOutSeconds() {
        return 1.5;
    }
}
