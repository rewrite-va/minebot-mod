package minebot.mod.gui;

import minebot.mod.config.Configs;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * ModMenu-hosted settings screen -- currently just the bot chat language
 * toggle (Configs.botLanguage), consumed by config.Messages. Structured
 * after mods/VillagerHelper's ConfigScreen (same button-list layout, same
 * Component.translatable pattern for its own labels via
 * assets/minebot-mod/lang/).
 */
public class ConfigScreen extends Screen {
    private static final String[] LANGUAGES = {"en", "es"};

    private final Screen parent;

    public ConfigScreen(Screen parent) {
        super(Component.translatable("minebot-mod.gui.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int btnWidth = 200;
        int x = this.width / 2 - btnWidth / 2;
        int y = 60;

        addRenderableWidget(Button.builder(
            Component.translatable("minebot-mod.gui.config.language", Configs.botLanguage),
            button -> {
                Configs.botLanguage = nextLanguage(Configs.botLanguage);
                button.setMessage(Component.translatable("minebot-mod.gui.config.language", Configs.botLanguage));
            }
        ).bounds(x, y, btnWidth, 20).build());

        y += 25;
        addRenderableWidget(Button.builder(
            CommonComponents.GUI_DONE,
            button -> onClose()
        ).bounds(x, y, btnWidth, 20).build());
    }

    private static String nextLanguage(String current) {
        for (int i = 0; i < LANGUAGES.length; i++) {
            if (LANGUAGES[i].equals(current)) {
                return LANGUAGES[(i + 1) % LANGUAGES.length];
            }
        }
        return LANGUAGES[0];
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        context.centeredText(font, title, this.width / 2, 20, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }
}
