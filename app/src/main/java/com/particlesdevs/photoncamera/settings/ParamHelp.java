package com.particlesdevs.photoncamera.settings;

import android.content.Context;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.processing.color.FilmLut;
import com.particlesdevs.photoncamera.processing.color.FilmSim;
import com.particlesdevs.photoncamera.processing.color.LensFilters;
import com.particlesdevs.photoncamera.util.Lang;

/**
 * What a LUT / lens filter / film / colour parameter does, shown on a long press (owner, 2026-10-28: «добавь описание для
 * параметров по длинному зажатию»): in the viewfinder's tray under its slider, on the film page as a help sheet. One text per
 * setting key, the same in both places; the directions of the film's exposures are those FilmSimTest checks on the model.
 */
public final class ParamHelp {
    private ParamHelp() {}

    /** Warmth / tint of «Цвет» (pref_scam_hybrid_colour_warmth / _tint). */
    public static final String WARMTH_KEY = "pref_scam_hybrid_colour_warmth", TINT_KEY = "pref_scam_hybrid_colour_tint";

    /** The description of the setting {@code key}, null when it has none. */
    @Nullable
    public static String of(@Nullable Context c, @Nullable String key) {
        if (key == null) return null;
        switch (key) {
            case FilmLut.STRENGTH_KEY:
                return Lang.t(c, "Насколько сильно LUT меняет цвет и тон: 100 % — полностью, 50 % — наполовину между снимком и LUT, 0 — LUT не действует.",
                        "How strongly the LUT changes the colour and tone: 100 % fully, 50 % halfway between the photo and the LUT, 0 no LUT.");
            case WARMTH_KEY:
                return Lang.t(c, "Делает снимок теплее (жёлтее) или холоднее (синее) поверх автоматического баланса белого. 0 — как определила камера. Действует и на плёнку.",
                        "Makes the photo warmer (yellower) or cooler (bluer) on top of the automatic white balance. 0 is the camera's own. Acts on a film too.");
            case TINT_KEY:
                return Lang.t(c, "Сдвигает цвет к пурпурному (вправо) или к зелёному (влево) поверх баланса белого — например, убрать зелень от ламп. 0 — как определила камера.",
                        "Shifts the colour toward magenta (right) or green (left) on top of the white balance, e.g. to remove the green of lamps. 0 is the camera's own.");
            case LensFilters.STRENGTH_KEY:
                return Lang.t(c, "Насколько заметен фильтр: свечение вокруг ярких мест, дымка или эффект фильтра. 0 — как без фильтра.",
                        "How visible the filter is: the glow round bright areas, the haze or the filter's effect. 0 is as without the filter.");
            case FilmSim.STRENGTH_KEY:
                return Lang.t(c, "Сколько плёнки в снимке: 100 % — снимок целиком отпечатан плёнкой, ниже — смешивается с обычной обработкой камеры, 0 — без плёнки.",
                        "How much film is in the photo: at 100 % the film prints the whole photo, below it is mixed with the camera's own look, 0 is no film.");
            case FilmSim.GRAIN_KEY:
                return Lang.t(c, "Сила зерна. Размер зерна задают сама плёнка (чем выше ISO, тем крупнее) и формат кадра (чем меньше кадр, тем крупнее). 100 % — как у этой плёнки, 0 — без зерна.",
                        "The grain's strength. Its size comes from the stock (the higher its ISO, the coarser) and the frame format (the smaller the frame, the coarser). 100 % is this film's, 0 no grain.");
            case FilmSim.HALATION_KEY:
                return Lang.t(c, "Красноватое свечение вокруг ярких источников: свет проходит плёнку, отражается от её основы и засвечивает слой ещё раз. 100 % — как у этой плёнки, 0 — без ореола.",
                        "The reddish glow round bright lights: light passes through the film, reflects off its base and exposes the layer again. 100 % is this film's, 0 no halation.");
            case FilmSim.SOFTNESS_KEY:
                return Lang.t(c, "Насколько плёнка размывает мелкие детали (её резкость, MTF). 100 % — как у этой плёнки, меньше — резче, 0 — без размытия плёнкой.",
                        "How much the film blurs fine detail (its sharpness, MTF). 100 % is this film's, less is sharper, 0 no film blur.");
            case FilmSim.EXPOSURE_KEY:
                return Lang.t(c, "Экспозиция плёнки, отпечаток с прежним временем: плюс — плотнее негатив и светлее снимок, минус — тоньше негатив и темнее снимок, тени гуще. У слайда — просто светлее или темнее. 1 — одна ступень.",
                        "The film's exposure, printed with the same time: plus is a denser negative and a lighter photo, minus a thinner negative and a darker photo with denser shadows. On a slide simply lighter or darker. 1 is one stop.");
            case FilmSim.PRINT_EV_KEY:
                return Lang.t(c, "Время экспозиции печати на бумагу: плюс — темнее отпечаток, минус — светлее. На слайды и обращаемые плёнки не действует: их не печатают.",
                        "The print's exposure on the paper: plus a darker print, minus a lighter one. No effect on slides and reversal films: they are not printed.");
            case FilmSim.TONE_KEY:
                return Lang.t(c, "Крутизна кривой плёнки при проявке: плюс — плотнее, темнее и насыщеннее, больше контраста в светах; минус — светлее, мягче и бледнее. 0 — как у этой плёнки. На ч/б плёнки не действует.",
                        "The steepness of the film's curve in development: plus is denser, darker and more saturated with more contrast in the highlights; minus lighter, softer and paler. 0 is this film's. No effect on black-and-white films.");
            case FilmSim.PRINT_M_KEY:
                return Lang.t(c, "Пурпурный фильтр увеличителя при печати, как в фотолаборатории: минус — пурпурнее, плюс — зеленее. Для слайдов не действует.",
                        "The enlarger's magenta filter, as in a darkroom: minus more magenta, plus greener. No effect on slides.");
            case FilmSim.PRINT_Y_KEY:
                return Lang.t(c, "Жёлтый фильтр увеличителя при печати: минус — желтее, плюс — синее. Для слайдов не действует.",
                        "The enlarger's yellow filter when printing: minus yellower, plus bluer. No effect on slides.");
            default:
                return null;
        }
    }

    /** A lens filter's own parameter («Длина лучей», «Положение перехода»…), null when it has none. */
    @Nullable
    public static String filterParam(@Nullable Context c, @Nullable String id) {
        final String name = LensFilters.paramName(c, id);
        if (name == null) return null;
        return Lang.t(c, "Своя настройка фильтра «" + LensFilters.name(c, id) + "»: " + LensFilters.description(c, id) + ".",
                "The «" + LensFilters.name(c, id) + "» filter's own setting: " + LensFilters.description(c, id) + ".");
    }

    /** A lens filter's fine control (LensFilters.T_*): what it does, from the same hint the settings page shows. */
    public static String filterControl(@Nullable Context c, @Nullable String id, int control) {
        final String hint = LensFilters.controlHint(c, id, control);
        return Lang.t(c, "Тонкая настройка: " + hint + ". Середина — как задумано.", "Fine tuning: " + hint + ". The middle is as designed.");
    }
}
