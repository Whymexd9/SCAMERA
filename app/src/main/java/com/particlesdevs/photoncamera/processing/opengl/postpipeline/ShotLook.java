package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.processing.color.ColourLook;
import com.particlesdevs.photoncamera.processing.color.FilmSim;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;

/**
 * The shot's look at its press (owner, 2026-10-28: «плёнка, LUT, теплота и оттенок по-прежнему читаются при обработке, а не в
 * момент нажатия … добавь в план»): the film simulation, the chosen LUT with its strength and the «Цвет» rows (warmth, tint and
 * the rest), as the viewfinder showed them when the shutter was pressed. The processing used to read them after the merge, ~5 s
 * later: a film, LUT or colour changed meanwhile (the tray) went into the photo taken before. Taken with LensFilter.Choice
 * (CaptureController), carried by the shot's saver to its processing (ScamProcessor -> PostPipeline.look); null there - the lab's
 * renders - reads the settings, under their overlay, as before.
 */
public final class ShotLook {
    /** The film simulation (FilmSim.current(): null without a stock or at strength 0). */
    @Nullable
    public final FilmSim.Settings film;
    /** The chosen LUT ("" none) and its strength. */
    public final String lut;
    public final float lutStrength;
    /** The «Цвет» rows (WhyTone.colourLook()). */
    public final ColourLook colour;

    ShotLook(@Nullable FilmSim.Settings film, String lut, float lutStrength, ColourLook colour) {
        this.film = film;
        this.lut = lut;
        this.lutStrength = lutStrength;
        this.colour = colour;
    }

    /** The look the settings give now (the press). */
    public static ShotLook now() {
        final float strength = PreferenceKeys.filmLutStrength();
        return new ShotLook(FilmSim.current(), strength > 0f ? PreferenceKeys.filmLut() : "", strength, WhyTone.colourLook());
    }

    @Override
    public String toString() {
        // P380: the whole recipe (a diffusion or the couplers changed meanwhile shows too)
        return "film " + (film == null ? "none" : film.recipe()) + ", LUT " + (lut.isEmpty() ? "none" : lut + " " + lutStrength);
    }
}
