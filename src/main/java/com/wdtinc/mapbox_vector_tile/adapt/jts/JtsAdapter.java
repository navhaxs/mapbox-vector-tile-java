package com.wdtinc.mapbox_vector_tile.adapt.jts;

import com.vividsolutions.jts.algorithm.CGAlgorithms;
import com.vividsolutions.jts.geom.*;
import com.vividsolutions.jts.geom.util.AffineTransformation;
import com.vividsolutions.jts.operation.valid.IsValidOp;
import com.vividsolutions.jts.simplify.TopologyPreservingSimplifier;
import com.wdtinc.mapbox_vector_tile.*;
import com.wdtinc.mapbox_vector_tile.MvtUtil;
import com.wdtinc.mapbox_vector_tile.encoding.GeomCmd;
import com.wdtinc.mapbox_vector_tile.encoding.ZigZag;
import com.wdtinc.mapbox_vector_tile.util.Vec2d;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Adapt JTS {@link Geometry} to 'Mapbox Vector Tile' objects.
 */
public final class JtsAdapter {

    /**
     * Create geometry clipped and then converted to MVT 'extent' coordinates.
     *
     * @param g original 'source' geometry
     * @param tileEnvelope world coordinate bounds for tile
     * @param geomFactory creates a geometry for the tile envelope
     * @param mvtParams specifies vector tile properties
     * @return clipped original geometry to the tile extents
     */
    public static List<Geometry> createTileGeom(Geometry g, Envelope tileEnvelope, GeometryFactory geomFactory,
                                                MvtParams mvtParams) {

        final Geometry tileEnvelopeGeom = geomFactory.toGeometry(tileEnvelope);

        final AffineTransformation t = new AffineTransformation();
        final double xDiff = tileEnvelope.getWidth();
        final double yDiff = tileEnvelope.getHeight();

        final double xOffset = -tileEnvelope.getMinX();
        final double yOffset = -tileEnvelope.getMinY();

        // Transform Setup: Shift to 0 as minimum value
        t.translate(xOffset, yOffset);

        // Transform Setup: Scale X and Y to tile extent values, flip Y values
        t.scale(1d / (xDiff / (double)mvtParams.extent),
                -1d / (yDiff / (double)mvtParams.extent));

        // Transform Setup: Bump Y values to positive quadrant
        t.translate(0d, (double)mvtParams.extent);


        // The area contained in BOTH the 'original geometry', g, AND the 'tile envelope geometry' is the 'tile geometry'
        final List<Geometry> intersectedGeoms = flatIntersection(tileEnvelopeGeom, g);
        final List<Geometry> transformedGeoms = new ArrayList<>(intersectedGeoms.size());

        // Transform intersected geometry
        Geometry nextTransformGeom;
        Object nextUserData;
        for(Geometry nextInterGeom : intersectedGeoms) {
            nextUserData = nextInterGeom.getUserData();

            nextTransformGeom = t.transform(nextInterGeom);

            // Floating --> Integer, still contained within doubles
            nextTransformGeom.apply(RoundingFilter.INSTANCE);

            nextTransformGeom = TopologyPreservingSimplifier.simplify(nextTransformGeom, .1d); // Can't use 0d, specify value < .5d

            nextTransformGeom.setUserData(nextUserData);

            transformedGeoms.add(nextTransformGeom);
        }

        return transformedGeoms;
    }

    /**
     * JTS does not support intersection on a {@link GeometryCollection}. This function works around this
     * by performing intersection on a flat list of geometry. The resulting list is pre-filtered for invalid
     * or empty geometry (outside of bounds). Invalid geometry are logged as errors.
     *
     * @param envelope non-list geometry defines bounding area
     * @param data geometry passed to {@link #flatFeatureList(Geometry)}
     * @return list of geometry from {@code data} intersecting with {@code envelope}.
     */
    private static List<Geometry> flatIntersection(Geometry envelope, Geometry data) {
        final List<Geometry> dataGeoms = flatFeatureList(data);
        final List<Geometry> intersectedGeoms = new ArrayList<>(dataGeoms.size());

        Geometry nextIntersected;
        for(Geometry nextGeom : dataGeoms) {
            try {
                final IsValidOp isValidOp = new IsValidOp(nextGeom);

                if(isValidOp.isValid()) {
                    nextIntersected = envelope.intersection(nextGeom);
                    if(!nextIntersected.isEmpty()) {
                        nextIntersected.setUserData(nextGeom.getUserData());
                        intersectedGeoms.add(nextIntersected);
                    }

                } else {
                    LoggerFactory.getLogger(JtsAdapter.class).error("{}", isValidOp.getValidationError());
                }

            } catch (TopologyException e) {
                LoggerFactory.getLogger(JtsAdapter.class).error(e.getMessage(), e);
            }
        }

        return intersectedGeoms;
    }

    /**
     * Get the MVT type mapping for the provided JTS Geometry.
     *
     * @param geometry JTS Geometry to get type for
     * @return MVT type for the given JTS Geometry, may return
     *     {@link com.wdtinc.mapbox_vector_tile.VectorTile.Tile.GeomType#UNKNOWN}
     */
    public static VectorTile.Tile.GeomType toGeomType(Geometry geometry) {
        VectorTile.Tile.GeomType result = VectorTile.Tile.GeomType.UNKNOWN;

        if(geometry instanceof Point
                || geometry instanceof MultiPoint) {
            result = VectorTile.Tile.GeomType.POINT;

        } else if(geometry instanceof LineString
                || geometry instanceof MultiLineString) {
            result = VectorTile.Tile.GeomType.LINESTRING;

        } else if(geometry instanceof Polygon
                || geometry instanceof MultiPolygon) {
            result = VectorTile.Tile.GeomType.POLYGON;
        }

        return result;
    }

    /**
     * <p>Recursively convert a {@link Geometry}, which may be an instance of {@link GeometryCollection} with mixed
     * element types, into a flat list containing only the following {@link Geometry} types:</p>
     * <ul>
     *     <li>{@link Point}</li>
     *     <li>{@link LineString}</li>
     *     <li>{@link Polygon}</li>
     *     <li>{@link MultiPoint}</li>
     *     <li>{@link MultiLineString}</li>
     *     <li>{@link MultiPolygon}</li>
     * </ul>
     * <p>WARNING: Any other Geometry types that were not mentioned in the list above will be discarded!</p>
     * <p>Useful for converting a generic geometry into a list of simple MVT-feature-ready geometries.</p>
     *
     * @param geom geometry to flatten
     * @return list of MVT-feature-ready geometries
     */
    public static List<Geometry> flatFeatureList(Geometry geom) {
        final List<Geometry> singleGeoms = new ArrayList<>();
        final Stack<Geometry> geomStack = new Stack<>();

        Geometry nextGeom;
        int nextGeomCount;

        geomStack.push(geom);
        while(!geomStack.isEmpty()) {
            nextGeom = geomStack.pop();

            if(nextGeom instanceof Point
                    || nextGeom instanceof MultiPoint
                    || nextGeom instanceof LineString
                    || nextGeom instanceof MultiLineString
                    || nextGeom instanceof Polygon
                    || nextGeom instanceof MultiPolygon) {

                singleGeoms.add(nextGeom);

            } else if(nextGeom instanceof GeometryCollection) {

                // Push all child geometries
                nextGeomCount = geom.getNumGeometries();
                for(int i = 0; i < nextGeomCount; ++i) {
                    geomStack.push(nextGeom.getGeometryN(i));
                }

            }
        }

        return singleGeoms;
    }

    // TODO: Support layer tags (feature attributes)

    /**
     * <p>Convert JTS {@link Geometry} to a list of vector tile features.
     * The Geometry should be in MVT coordinates.</p>
     *
     * <p>Each geometry will have its own ID.</p>
     *
     * @param geometry JTS geometry to convert
     * @param filter determines which geometry to accept or reject for inclusion in the feature
     * @param layerProps layer properties for tagging features
     * @param userDataConverter convert {@link Geometry#userData} to MVT feature tags
     */
    public static List<VectorTile.Tile.Feature> toFeatures(Geometry geometry, IGeometryFilter filter,
                                                           MvtLayerProps layerProps, IUserDataConverter userDataConverter) {
        return toFeatures(flatFeatureList(geometry), filter, layerProps, userDataConverter);
    }

    /**
     * <p>Convert a flat list of JTS {@link Geometry} to a list of vector tile features.
     * The Geometry should be in MVT coordinates.</p>
     *
     * <p>Each geometry will have its own ID.</p>
     *
     * @param flatGeoms flat list of JTS geometry to convert
     * @param filter determines which geometry to accept or reject for inclusion in the feature
     * @param layerProps layer properties for tagging features
     * @param userDataConverter convert {@link Geometry#userData} to MVT feature tags
     */
    public static List<VectorTile.Tile.Feature> toFeatures(Collection<Geometry> flatGeoms, IGeometryFilter filter,
                                                           MvtLayerProps layerProps, IUserDataConverter userDataConverter) {

        // Guard: empty geometry
        if(flatGeoms.isEmpty()) {
            return Collections.emptyList();
        }

        final List<VectorTile.Tile.Feature> features = new ArrayList<>();
        final Vec2d cursor = new Vec2d();

        int nextFeatureId = 1; // TODO: Need better feature ID handling..., or don't bother
        VectorTile.Tile.Feature nextFeature;

        for(Geometry nextGeom : flatGeoms) {

            // TODO: Could easily move outside this function...
            if(!filter.accept(nextGeom)) {
                continue;
            }

            cursor.set(0d, 0d);
            nextFeature = toFeature(nextGeom, cursor, nextFeatureId++, layerProps, userDataConverter);
            if(nextFeature != null) {
                features.add(nextFeature);
            }
        }

        return features;
    }

    /**
     * Create and return a feature from a geometry. Returns null on failure.
     *
     * @param geom flat geometry via {@link #flatFeatureList(Geometry)} that can be translated to a feature
     * @param cursor vector tile cursor position
     * @param featureId id value to apply to the feature
     * @param layerProps layer properties for tagging features
     * @return new tile feature instance, or null on failure
     */
    private static VectorTile.Tile.Feature toFeature(Geometry geom, Vec2d cursor, int featureId,
                                                     MvtLayerProps layerProps, IUserDataConverter userDataConverter) {

        // Guard: UNKNOWN Geometry
        final VectorTile.Tile.GeomType mvtGeomType = JtsAdapter.toGeomType(geom);
        if(mvtGeomType == VectorTile.Tile.GeomType.UNKNOWN) {
            return null;
        }


        final VectorTile.Tile.Feature.Builder featureBuilder = VectorTile.Tile.Feature.newBuilder();
        final boolean mvtClosePath = MvtUtil.shouldClosePath(mvtGeomType);
        final List<Integer> mvtGeom = new ArrayList<>();

        featureBuilder.setId(featureId);
        featureBuilder.setType(mvtGeomType);

        if(geom instanceof Point || geom instanceof MultiPoint) {

            // Encode as MVT point or multipoint
            mvtGeom.addAll(ptsToGeomCmds(geom, cursor));

        } else if(geom instanceof LineString || geom instanceof MultiLineString) {

            // Encode as MVT linestring or multi-linestring
            for (int i = 0; i < geom.getNumGeometries(); ++i) {
                mvtGeom.addAll(linesToGeomCmds(geom.getGeometryN(i), mvtClosePath, cursor, 1));
            }

        } else if(geom instanceof MultiPolygon || geom instanceof Polygon) {

            // Encode as MVT polygon or multi-polygon
            for(int i = 0; i < geom.getNumGeometries(); ++i) {

                final Polygon nextPoly = (Polygon) geom.getGeometryN(i);
                final List<Integer> nextPolyGeom = new ArrayList<>();
                boolean valid = true;

                // Add exterior ring
                final LineString exteriorRing = nextPoly.getExteriorRing();

                // Area must be non-zero
                final double exteriorArea = CGAlgorithms.signedArea(exteriorRing.getCoordinates());
                if(((int) Math.round(exteriorArea)) == 0) {
                    continue;
                }

                // Check CCW Winding (must be positive area)
                if(exteriorArea < 0d) {
                    CoordinateArrays.reverse(exteriorRing.getCoordinates());
                }

                nextPolyGeom.addAll(linesToGeomCmds(exteriorRing, mvtClosePath, cursor, 2));


                // Add interior rings
                for(int ringIndex = 0; ringIndex < nextPoly.getNumInteriorRing(); ++ringIndex) {

                    final LineString nextInteriorRing = nextPoly.getInteriorRingN(ringIndex);

                    // Area must be non-zero
                    final double interiorArea = CGAlgorithms.signedArea(nextInteriorRing.getCoordinates());
                    if(((int)Math.round(interiorArea)) == 0) {
                        continue;
                    }

                    // Check CW Winding (must be negative area)
                    if(interiorArea > 0d) {
                        CoordinateArrays.reverse(nextInteriorRing.getCoordinates());
                    }

                    // Interior ring area must be < exterior ring area, or entire geometry is invalid
                    if(Math.abs(exteriorArea) <= Math.abs(interiorArea)) {
                        valid = false;
                        break;
                    }

                    nextPolyGeom.addAll(linesToGeomCmds(nextInteriorRing, mvtClosePath, cursor, 2));
                }


                if(valid) {
                    mvtGeom.addAll(nextPolyGeom);
                }
            }
        }


        if(mvtGeom.size() < 1) {
            return null;
        }

        featureBuilder.addAllGeometry(mvtGeom);


        // Feature Properties
        userDataConverter.addTags(geom.getUserData(), layerProps, featureBuilder);

        return featureBuilder.build();
    }

    /**
     * <p>Convert a {@link Point} or {@link MultiPoint} geometry to a list of MVT geometry drawing commands. See
     * <a href="https://github.com/mapbox/vector-tile-spec">vector-tile-spec</a>
     * for details.</p>
     *
     * <p>WARNING: The value of the {@code cursor} parameter is modified as a result of calling this method.</p>
     *
     * @param geom input of type {@link Point} or {@link MultiPoint}. Type is NOT checked and expected to be correct.
     * @param cursor modified during processing to contain next MVT cursor position
     * @return list of commands
     */
    private static List<Integer> ptsToGeomCmds(final Geometry geom, final Vec2d cursor) {

        // Guard: empty geometry coordinates
        final Coordinate[] geomCoords = geom.getCoordinates();
        if(geomCoords.length <= 0) {
            Collections.emptyList();
        }


        /** Tile commands and parameters */
        final List<Integer> geomCmds = new ArrayList<>(geomCmdBuffLenPts(geomCoords.length));

        /** Holds next MVT coordinate */
        final Vec2d mvtPos = new Vec2d();

        /** Length of 'MoveTo' draw command */
        int moveCmdLen = 0;

        // Insert placeholder for 'MoveTo' command header
        geomCmds.add(0);

        Coordinate nextCoord;

        for(int i = 0; i < geomCoords.length; ++i) {
            nextCoord = geomCoords[i];
            mvtPos.set(nextCoord.x, nextCoord.y);

            // Ignore duplicate MVT points
            if(i == 0 || !equalAsInts(cursor, mvtPos)) {
                ++moveCmdLen;
                moveCursor(cursor, geomCmds, mvtPos);
            }
        }


        if(moveCmdLen <= GeomCmd.CMD_HDR_LEN_MAX) {

            // Write 'MoveTo' command header to first index
            geomCmds.set(0, GeomCmd.cmdHdr(Command.MoveTo, moveCmdLen));

            return geomCmds;

        } else {

            // Invalid geometry, need at least 1 'LineTo' value to make a Multiline or Polygon
            return Collections.emptyList();
        }
    }

    /**
     * <p>Convert a {@link LineString} or {@link Polygon} to a list of MVT geometry drawing commands.
     * A {@link MultiLineString} or {@link MultiPolygon} can be encoded by calling this method multiple times.</p>
     *
     * <p>See <a href="https://github.com/mapbox/vector-tile-spec">vector-tile-spec</a> for details.</p>
     *
     * <p>WARNING: The value of the {@code cursor} parameter is modified as a result of calling this method.</p>
     *
     * @param geom input of type {@link LineString} or {@link Polygon}. Type is NOT checked and expected to be correct.
     * @param closeEnabled whether a 'ClosePath' command should terminate the command list
     * @param cursor modified during processing to contain next MVT cursor position
     * @param minLineToLen minimum allowed length for LineTo command.
     * @return list of commands
     */
    private static List<Integer> linesToGeomCmds(
            final Geometry geom,
            final boolean closeEnabled,
            final Vec2d cursor,
            final int minLineToLen) {

        // Guard: Not enough geometry coordinates for a line
        final Coordinate[] geomCoords = geom.getCoordinates();
        if(geomCoords.length < 2) {
            Collections.emptyList();
        }


        /** Tile commands and parameters */
        final List<Integer> geomCmds = new ArrayList<>(geomCmdBuffLenLines(geomCoords.length, closeEnabled));

        /** Holds next MVT coordinate */
        final Vec2d mvtPos = new Vec2d();

        // Initial coordinate
        Coordinate nextCoord = geomCoords[0];
        mvtPos.set(nextCoord.x, nextCoord.y);

        // Encode initial 'MoveTo' command
        geomCmds.add(GeomCmd.cmdHdr(Command.MoveTo, 1));

        moveCursor(cursor, geomCmds, mvtPos);


        /** Index of 'LineTo' 'command header' */
        final int lineToCmdHdrIndex = geomCmds.size();

        // Insert placeholder for 'LineTo' command header
        geomCmds.add(0);


        /** Length of 'LineTo' draw command */
        int lineToLength = 0;


        for(int i = 1; i < geomCoords.length - 1; ++i) {
            nextCoord = geomCoords[i];
            mvtPos.set(nextCoord.x, nextCoord.y);

            // Ignore duplicate MVT points in sequence
            if(!equalAsInts(cursor, mvtPos)) {
                ++lineToLength;
                moveCursor(cursor, geomCmds, mvtPos);
            }
        }


        // Final coordinate
        nextCoord = geomCoords[geomCoords.length - 1];
        mvtPos.set(nextCoord.x, nextCoord.y);

        // Ignore duplicate MVT points
        // Ignore final coordinates equivalent to a 'ClosePath'
        if(!equalAsInts(cursor, mvtPos) && (!closeEnabled || !geomCoords[0].equals(nextCoord))) {
            ++lineToLength;
            moveCursor(cursor, geomCmds, mvtPos);
        }


        if(lineToLength >= minLineToLen && lineToLength <= GeomCmd.CMD_HDR_LEN_MAX) {

            // Write 'LineTo' 'command header'
            geomCmds.set(lineToCmdHdrIndex, GeomCmd.cmdHdr(Command.LineTo, lineToLength));

            if(closeEnabled) {
                geomCmds.add(GeomCmd.closePathCmdHdr());
            }

            return geomCmds;

        } else {

            // Invalid geometry, need at least 1 'LineTo' value to make a Multiline or Polygon
            return Collections.emptyList();
        }
    }

    /**
     * <p>Appends {@link ZigZag#encode(int)} of delta in x,y from {@code cursor} to {@code mvtPos} into the {@code geomCmds} buffer.</p>
     *
     * <p>Afterwards, the {@code cursor} values are changed to match the {@code mvtPos} values.</p>
     *
     * @param cursor MVT cursor position
     * @param geomCmds geometry command list
     * @param mvtPos next MVT cursor position
     */
    private static void moveCursor(Vec2d cursor, List<Integer> geomCmds, Vec2d mvtPos) {

        // Delta, then zigzag
        geomCmds.add(ZigZag.encode((int)mvtPos.x - (int)cursor.x));
        geomCmds.add(ZigZag.encode((int)mvtPos.y - (int)cursor.y));

        cursor.set(mvtPos);
    }

    /**
     * Return true if the values of the two vectors are equal when cast as ints.
     *
     * @param a first vector to compare
     * @param b second vector to compare
     * @return true if the values of the two vectors are equal when cast as ints
     */
    private static boolean equalAsInts(Vec2d a, Vec2d b) {
        return (int) b.x == (int) a.x && (int) b.y == (int) a.y;
    }

    /**
     * Get required geometry buffer size for a {@link Point} or {@link MultiPoint} geometry.
     *
     * @param coordCount coordinate count for the geometry
     * @return required geometry buffer length
     */
    public static int geomCmdBuffLenPts(int coordCount) {

        // 1 MoveTo Header, 2 parameters * coordCount
        return 1 + (coordCount * 2);
    }

    /**
     * Get required geometry buffer size for a {@link LineString} or {@link Polygon} geometry.
     *
     * @param coordCount coordinate count for the geometry
     * @param closeEnabled whether a 'ClosePath' command should terminate the command list
     * @return required geometry buffer length
     */
    public static int geomCmdBuffLenLines(int coordCount, boolean closeEnabled) {

        // MoveTo Header, LineTo Header, Optional ClosePath Header, 2 parameters * coordCount
        return 2 + (closeEnabled ? 1 : 0) + (coordCount * 2);
    }
}
