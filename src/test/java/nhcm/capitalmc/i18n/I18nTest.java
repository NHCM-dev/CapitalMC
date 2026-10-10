package nhcm.capitalmc.i18n;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class I18nTest
{
    @Test
    void colorsAndFormattingBecomeComponentStylesWithoutVisibleCodes()
    {
        var text = I18n.styled("&aGreen &lBold\u00a7rPlain &#12abefHex");
        assertEquals("Green BoldPlain Hex", text.getString());
        var parts = text.getSiblings();
        assertEquals(0x55ff55, parts.get(0).getStyle().getColor().getValue());
        assertTrue(parts.get(1).getStyle().isBold());
        assertEquals(0x55ff55, parts.get(1).getStyle().getColor().getValue());
        assertFalse(parts.get(2).getStyle().isBold());
        assertNull(parts.get(2).getStyle().getColor());
        assertEquals(0x12abef, parts.get(3).getStyle().getColor().getValue());
    }

    @Test
    void unknownCodesStayLiteralAndFormattedTranslationsKeepColors()
    {
        assertEquals("A&Q &z &#bad &#+abcde", I18n.styled("A&Q &z &#bad &#+abcde").getString());
        assertEquals("[失败] 错误", I18n.text("zh_cn", "ui.error", "错误").getString());
        assertEquals(0xff5555, I18n.text("zh_cn", "ui.error", "错误").getSiblings().getFirst().getStyle().getColor().getValue());
        var lore = I18n.lines("en_us", "&aFirst\n&cSecond");
        assertEquals(2, lore.size());
        assertEquals(0xff5555, lore.get(1).getSiblings().getFirst().getStyle().getColor().getValue());
        assertEquals(0x55ff55, I18n.lines("en_us", "&aFirst\nSecond").get(1).getSiblings().getFirst().getStyle().getColor().getValue());
    }

    @Test
    void coloredLoreTranslationsSurviveFormattingAndMarginHintExistsInBothLanguages()
    {
        for (String language : new String[]{"en_us", "zh_cn"})
        {
            String raw = I18n.format(language, "ui.trade.amounts", "Diamond", "1.5", "Gold", "3.75");
            assertTrue(raw.contains("&b1.5"));
            assertTrue(raw.contains("&63.75"));
            assertEquals(2, I18n.lines(language, raw).size());
            assertNotEquals("ui.trade.toggle_margin", I18n.format(language, "ui.trade.toggle_margin"));
        }
    }

    @Test
    void guidedQuestionsAndConfirmationKeepLabelsUnitsAndPercentSigns()
    {
        for (String language : new String[]{"en_us", "zh_cn"})
        {
            String label = I18n.format(language, "ui.form.field.feePercent");
            String step = I18n.text(language, "ui.form.step", 5, 10, label).getString();
            assertTrue(step.contains("5/10"));
            assertTrue(step.contains("%"));
            assertFalse(step.contains("ui.form."));
            String price = I18n.text(language, "ui.input.price", "Gold", "Diamond").getString();
            assertTrue(price.contains("Gold") && price.contains("1 Diamond"));
            String confirmation = I18n.text(language, "ui.form.confirm_action", "Rename", "Diamond").getString();
            assertTrue(confirmation.contains("Rename") && confirmation.contains("Diamond") && confirmation.contains("yes"));
            assertFalse(confirmation.contains("%s") || confirmation.contains("%1$s"));
            assertTrue(I18n.text(language, "ui.form.percent").getString().contains("0.05%"));
        }
    }
}
