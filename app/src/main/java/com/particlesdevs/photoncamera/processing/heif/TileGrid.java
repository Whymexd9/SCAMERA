package com.particlesdevs.photoncamera.processing.heif;

/**
 * The grid of equal tiles a HEIC image is coded in (ISO/IEC 23008-12 6.6.2.3): {@link #columns} x {@link #rows} tiles of
 * {@link #tile} x {@link #tile} pixels in raster order; the right column and the bottom row reach past the image and are
 * padded (edge replication), the decoder crops the grid to {@link #width} x {@link #height}. 512 x 512 tiles as HeifWriter's
 * grid mode uses. Pure Java.
 */
public final class TileGrid {
    /** Tile side of the 10-bit HEIC (HeifWriter's grid tile). */
    public static final int TILE = 512;
    /** ImageGrid stores rows_minus_one / columns_minus_one in 8 bits. */
    public static final int MAX_TILES_PER_SIDE = 256;

    public final int width, height, tile, columns, rows;

    public TileGrid(int width, int height, int tile) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("image " + width + "x" + height);
        if (tile < 16 || (tile & 1) != 0) throw new IllegalArgumentException("tile " + tile);
        this.width = width;
        this.height = height;
        this.tile = tile;
        this.columns = (width + tile - 1) / tile;
        this.rows = (height + tile - 1) / tile;
        if (columns > MAX_TILES_PER_SIDE || rows > MAX_TILES_PER_SIDE)
            throw new IllegalArgumentException(width + "x" + height + " needs " + columns + "x" + rows + " tiles (max " + MAX_TILES_PER_SIDE + " per side)");
    }

    public TileGrid(int width, int height) {
        this(width, height, TILE);
    }

    public int count() {
        return columns * rows;
    }

    /** First image column / row of a tile. */
    public int x0(int column) {
        return column * tile;
    }

    public int y0(int row) {
        return row * tile;
    }

    /** Image columns inside tile column {@code column} (the rest of the tile is padding). */
    public int validWidth(int column) {
        return Math.min(tile, width - x0(column));
    }

    /** Image rows inside tile row {@code row}. */
    public int validHeight(int row) {
        return Math.min(tile, height - y0(row));
    }

    @Override
    public String toString() {
        return columns + "x" + rows + " tiles of " + tile + " for " + width + "x" + height;
    }
}
