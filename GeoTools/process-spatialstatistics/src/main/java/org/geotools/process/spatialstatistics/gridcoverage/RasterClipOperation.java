/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2014, Open Source Geospatial Foundation (OSGeo)
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */
package org.geotools.process.spatialstatistics.gridcoverage;

import java.awt.geom.AffineTransform;
import java.util.logging.Logger;

import javax.media.jai.BorderExtenderConstant;
import javax.media.jai.JAI;
import javax.media.jai.ParameterBlockJAI;
import javax.media.jai.PlanarImage;
import javax.media.jai.iterator.RectIter;
import javax.media.jai.iterator.RectIterFactory;

import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridGeometry2D;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.process.spatialstatistics.core.SSUtils;
import org.geotools.util.logging.Logging;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import org.locationtech.jts.geom.Geometry;

/**
 * Creates a spatial subset of a raster dataset.
 * 
 * @author Minpa Lee, MangoSystem
 * 
 * @source $URL$
 */
public class RasterClipOperation extends RasterProcessingOperation {
    protected static final Logger LOGGER = Logging.getLogger(RasterClipOperation.class);

    public GridCoverage2D execute(GridCoverage2D inputCoverage, Geometry cropShape) {
        // must be same CRS!
        GridCoverage2D clipped = null;
        cropShape = transformGeometry(cropShape, inputCoverage.getCoordinateReferenceSystem());

        ReferencedEnvelope gridExtent = new ReferencedEnvelope(inputCoverage.getEnvelope());
        RasterCropOperation cropOp = new RasterCropOperation();
        if (gridExtent.contains(cropShape.getEnvelopeInternal())) {
            clipped = cropOp.execute(inputCoverage, cropShape);
        } else {
            // resize extent
            CoordinateReferenceSystem crs = inputCoverage.getCoordinateReferenceSystem();
            ReferencedEnvelope extent = new ReferencedEnvelope(cropShape.getEnvelopeInternal(), crs);
            clipped = cropOp.execute(execute(inputCoverage, extent), cropShape);
        }

        return clipped;
    }

    public GridCoverage2D execute(GridCoverage2D inputCoverage, ReferencedEnvelope extent) {
        // must be same CRS!
        GridCoverage2D clipped = null;

        ReferencedEnvelope gridExtent = new ReferencedEnvelope(inputCoverage.getEnvelope());
        if (gridExtent.contains((org.locationtech.jts.geom.Envelope) extent)) {
            RasterCropOperation cropOp = new RasterCropOperation();
            clipped = cropOp.execute(inputCoverage, extent);
        } else {
            NoData = RasterHelper.getNoDataValue(inputCoverage);

            GridGeometry2D gridGeometry2D = inputCoverage.getGridGeometry();
            AffineTransform gridToWorld = (AffineTransform) gridGeometry2D.getGridToCRS2D();

            CellSizeX = Math.abs(gridToWorld.getScaleX());
            CellSizeY = Math.abs(gridToWorld.getScaleY());
            Extent = RasterHelper.getResolvedEnvelope(extent, CellSizeX, CellSizeY);

            int bandCount = inputCoverage.getNumSampleDimensions();

            // calculate pad
            int leftPad = calculatePad(gridExtent.getMinX(), Extent.getMinX(), CellSizeX, false);
            int rightPad = calculatePad(gridExtent.getMaxX(), Extent.getMaxX(), CellSizeX, true);
            int bottomPad = calculatePad(gridExtent.getMinY(), Extent.getMinY(), CellSizeY, false);
            int topPad = calculatePad(gridExtent.getMaxY(), Extent.getMaxY(), CellSizeY, true);

            final PlanarImage inputImage = (PlanarImage) inputCoverage.getRenderedImage();

            // resize board [leftPad, rightPad, topPad, bottomPad, type]
            ParameterBlockJAI parameterBlock = new ParameterBlockJAI("border", "rendered");
            parameterBlock.addSource(inputImage);
            parameterBlock.setParameter("leftPad", leftPad);
            parameterBlock.setParameter("rightPad", rightPad);
            parameterBlock.setParameter("bottomPad", bottomPad);
            parameterBlock.setParameter("topPad", topPad);
            final double[] fillValue = new double[bandCount];
            for (int index = 0; index < fillValue.length; index++) {
                fillValue[index] = bandCount == 1 ? NoData : -1;
            }
            parameterBlock.setParameter("type", new BorderExtenderConstant(fillValue));
            PlanarImage outputImage = JAI.create("border", parameterBlock);

            // resize extent
            if (bandCount > 1) {
                NoData = -1;
                MaxValue = 255;
                MinValue = 0;
                clipped = createGridCoverage(inputCoverage.getName(), outputImage,
                        inputCoverage.getSampleDimensions(), NoData, MinValue, MaxValue, Extent);
            } else {
                RectIter readIter = RectIterFactory.create(outputImage, outputImage.getBounds());
                readIter.startLines();
                while (!readIter.finishedLines()) {
                    readIter.startPixels();
                    while (!readIter.finishedPixels()) {
                        double sampleValue = readIter.getSampleDouble(0);
                        if (!SSUtils.compareDouble(sampleValue, NoData)) {
                            MaxValue = Math.max(MaxValue, sampleValue);
                            MinValue = Math.min(MinValue, sampleValue);
                        }
                        readIter.nextPixel();
                    }
                    readIter.nextLine();
                }
                clipped = createGridCoverage(inputCoverage.getName(), outputImage);
            }
        }

        return clipped;
    }

    private int calculatePad(double origin, double dest, double cellSize, boolean isMax) {
        double bandWidth = origin - dest;

        if (bandWidth == 0) {
            return 0;
        } else if (bandWidth < 0) {
            if (isMax) {
                return (int) Math.floor((Math.abs(bandWidth) / cellSize) + 0.5d);
            } else {
                return -(int) Math.floor((Math.abs(bandWidth) / cellSize) + 0.5d);
            }
        } else {
            if (isMax) {
                return -(int) Math.floor((bandWidth / cellSize) + 0.5d);
            } else {
                return (int) Math.floor((bandWidth / cellSize) + 0.5d);
            }
        }
    }
}
