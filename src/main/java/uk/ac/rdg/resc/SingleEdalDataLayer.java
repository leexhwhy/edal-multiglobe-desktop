/*
Copyright (C) 2001, 2006 United States Government
as represented by the Administrator of the
National Aeronautics and Space Administration.
All Rights Reserved.
 */
package uk.ac.rdg.resc;

import gov.nasa.worldwind.WorldWind;
import gov.nasa.worldwind.avlist.AVKey;
import gov.nasa.worldwind.avlist.AVList;
import gov.nasa.worldwind.avlist.AVListImpl;
import gov.nasa.worldwind.event.SelectEvent;
import gov.nasa.worldwind.event.SelectListener;
import gov.nasa.worldwind.geom.Angle;
import gov.nasa.worldwind.geom.LatLon;
import gov.nasa.worldwind.geom.Sector;
import gov.nasa.worldwind.layers.BasicTiledImageLayer;
import gov.nasa.worldwind.layers.TextureTile;
import gov.nasa.worldwind.util.LevelSet;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.geotoolkit.referencing.crs.DefaultGeographicCRS;
import org.joda.time.DateTime;
import org.joda.time.chrono.ISOChronology;

import uk.ac.rdg.resc.edal.domain.Extent;
import uk.ac.rdg.resc.edal.exceptions.EdalException;
import uk.ac.rdg.resc.edal.geometry.BoundingBox;
import uk.ac.rdg.resc.edal.geometry.BoundingBoxImpl;
import uk.ac.rdg.resc.edal.graphics.style.ColourScale;
import uk.ac.rdg.resc.edal.graphics.style.ColourScheme;
import uk.ac.rdg.resc.edal.graphics.style.MapImage;
import uk.ac.rdg.resc.edal.graphics.style.RasterLayer;
import uk.ac.rdg.resc.edal.graphics.style.SegmentColourScheme;
import uk.ac.rdg.resc.edal.metadata.VariableMetadata;
import uk.ac.rdg.resc.edal.util.PlottingDomainParams;
import uk.ac.rdg.resc.old.EdalDataLayer;

/**
 * This is a layer which displays EDAL data and can change data.
 * 
 * Not used at the moment. See {@link EdalDataLayer} for a layer which can't
 * change data - instead you change the layer
 */
public class SingleEdalDataLayer extends BasicTiledImageLayer implements SelectListener {
    private VideoWallCatalogue catalogue;
    private MapImage mapImage = null;

    private String currentLayerName = null;

    private DateTime time = new DateTime(2010, 07, 15, 12, 00, ISOChronology.getInstanceUTC());
    private Double elevation = 5.0;

    public SingleEdalDataLayer(String layerId, VideoWallCatalogue catalogue) {
        super(makeLevels(layerId));
        /*
         * Make this layer's cache expire immediately. This will clear out data
         * from any previous runs (since the IDs don't change between runs)
         */
        setExpiryTime(System.currentTimeMillis());
        this.catalogue = catalogue;
        setName(layerId);
        this.setUseTransparentTextures(true);
        setPickEnabled(true);
        setEnabled(false);
    }

    public String getDataLayerName() {
        return currentLayerName;
    }

    public boolean isShowingData() {
        return !(currentLayerName == null || currentLayerName.equals(""));
    }

    public synchronized void setData(String layerName) {
        if (layerName == null) {
            /*
             * Setting this layer to display no data
             */
            setEnabled(false);
            mapImage = null;
            return;
        }

        Extent<Float> scaleRange;
        try {
            scaleRange = catalogue.getRangeForLayer(layerName);
        } catch (EdalException e) {
            e.printStackTrace();
            /*
             * TODO log this properly
             */
            return;
        }
        if (layerName.equals(currentLayerName)) {
            return;
        } else {
            currentLayerName = layerName;
        }

        try {
            VariableMetadata metadata = catalogue.getVariableMetadataForLayer(layerName);
            /*
             * If the time/elevation values are not valid then set them to a
             * default value
             */
            if (metadata.getTemporalDomain() != null
                    && !metadata.getTemporalDomain().contains(time)) {
                time = metadata.getTemporalDomain().getExtent().getHigh();
            }
            if (metadata.getVerticalDomain() != null
                    && !metadata.getVerticalDomain().contains(elevation)) {
                elevation = metadata.getVerticalDomain().getExtent().getLow();
            }
        } catch (EdalException e) {
            e.printStackTrace();
            /*
             * TODO log this properly
             */
            return;
        }

        /*
         * TODO use XML styles...
         */
        ColourScheme colourScheme = new SegmentColourScheme(new ColourScale(scaleRange.getLow(),
                scaleRange.getHigh(), false), Color.black, Color.black, new Color(0, true),
                "rainbow", 100);
        RasterLayer rasterLayer = new RasterLayer(layerName, colourScheme);
        mapImage = new MapImage();
        mapImage.getLayers().add(rasterLayer);

        /*
         * Expire the old images
         */
        setExpiryTime(System.currentTimeMillis() - 1000L);
        String location = WorldWind.getDataFileStore().getWriteLocation().getAbsolutePath();
        System.out.println("Expiring data at: " + location);
        //        File cache = new File(location);
        //        boolean delete = cache.delete();
        //        System.out.println("deleted cache: "+delete);
        firePropertyChange(AVKey.LAYER, null, this);
        setEnabled(true);
    }

    @Override
    protected synchronized void retrieveRemoteTexture(TextureTile tile,
            DownloadPostProcessor postProcessor) {
        //        String pathname = tile.getPath();
        //        if(pathname.contains("edal-layer-0")) {
        //            System.out.println("Generating image of "+currentLayerName+": "+tile.getPath());
        //        }
        final File outFile = WorldWind.getDataFileStore().newFile(tile.getPath());
        if (outFile == null) {
            return;
        }

        if (outFile.exists()) {
            return;
        }

        // Create and save tile texture image
        BufferedImage image = new BufferedImage(tile.getLevel().getTileWidth(), tile.getLevel()
                .getTileHeight(), BufferedImage.TYPE_4BYTE_ABGR);
        image = createTileImage(tile, image);
        try {
            ImageIO.write(image, "png", outFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    protected BufferedImage createTileImage(TextureTile tile, BufferedImage image) {
        if (mapImage == null) {
            return image;
        }

        int width = tile.getLevel().getTileWidth();
        int height = tile.getLevel().getTileHeight();
        Sector s = tile.getSector();
        BoundingBox bbox = new BoundingBoxImpl(s.getMinLongitude().degrees,
                s.getMinLatitude().degrees, s.getMaxLongitude().degrees,
                s.getMaxLatitude().degrees, DefaultGeographicCRS.WGS84);
        /*
         * TODO make this a field and update it when necessary
         */
        PlottingDomainParams params = new PlottingDomainParams(width, height, bbox, null, null,
                null, elevation, time);

        try {
            BufferedImage ret = mapImage.drawImage(params, catalogue);
            return ret;
        } catch (EdalException e) {
            e.printStackTrace();
            // Draw a red cross on white background
            Graphics2D g2 = image.createGraphics();
            g2.setPaint(Color.WHITE);
            g2.fillRect(0, 0, width, height);
            g2.setPaint(Color.RED);
            g2.drawLine(0, 0, width, height);
            g2.drawLine(0, height, width, 0);
            return image;
        }
    }

    private static LevelSet makeLevels(String layerId) {
        AVList params = new AVListImpl();

        params.setValue(AVKey.TILE_WIDTH, 128);
        params.setValue(AVKey.TILE_HEIGHT, 128);
        params.setValue(AVKey.DATA_CACHE_NAME, "EDAL/Tiles/" + layerId);
        params.setValue(AVKey.SERVICE, "*");
        params.setValue(AVKey.DATASET_NAME, layerId);
        params.setValue(AVKey.FORMAT_SUFFIX, ".png");
        params.setValue(AVKey.NUM_LEVELS, 10);
        params.setValue(AVKey.NUM_EMPTY_LEVELS, 0);
        params.setValue(AVKey.LEVEL_ZERO_TILE_DELTA,
                new LatLon(Angle.fromDegrees(36d), Angle.fromDegrees(36d)));
        params.setValue(AVKey.SECTOR, Sector.FULL_SPHERE);

        return new LevelSet(params);
    }

    @Override
    public String toString() {
        return "Edal Data Layer";
    }

    @Override
    public void selected(SelectEvent event) {
        System.out.println("select event");
    }
}