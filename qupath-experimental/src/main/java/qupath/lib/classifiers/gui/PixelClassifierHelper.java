package qupath.lib.classifiers.gui;

import org.bytedeco.javacpp.opencv_core;
import org.bytedeco.javacpp.opencv_imgproc;
import org.bytedeco.javacpp.opencv_ml;
import org.bytedeco.javacpp.opencv_ml.TrainData;
import org.bytedeco.javacpp.indexer.UByteIndexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.classifiers.Normalization;
import qupath.lib.classifiers.opencv.OpenCVClassifiers.FeaturePreprocessor;
import qupath.lib.classifiers.opencv.OpenCVClassifiers;
import qupath.lib.classifiers.pixel.PixelClassifierOutputChannel;
import qupath.lib.classifiers.pixel.features.OpenCVFeatureCalculator;
import qupath.lib.gui.images.stores.ImageRegionStoreHelpers;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;

import org.bytedeco.javacpp.opencv_core.Mat;
import org.bytedeco.javacpp.opencv_core.MatVector;

import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.classes.PathClassFactory;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyEvent;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyListener;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.PathROIToolsAwt;
import qupath.lib.roi.interfaces.PathArea;
import qupath.lib.roi.interfaces.PathLine;
import qupath.lib.roi.interfaces.ROI;
import qupath.opencv.processing.OpenCVTools;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.stream.Collectors;


public class PixelClassifierHelper implements PathObjectHierarchyListener {
	
	private static final Logger logger = LoggerFactory.getLogger(PixelClassifierHelper.class);

    private ImageData<BufferedImage> imageData;
    private OpenCVFeatureCalculator calculator;
    private boolean changes = true;

    private List<PixelClassifierOutputChannel> channels;
    private double downsample;
    
    private Mat matTraining;
    private Mat matTargets;
    
    private FeaturePreprocessor preprocessor;

    private Map<ROI, Mat> cacheFeatures = new WeakHashMap<>();

    /**
     * Create a new pixel classifier helper, to support generating training data.
     * 
     * @param imageData
     * @param calculator
     * @param requestedPixelSizeMicrons
     */
    public PixelClassifierHelper(ImageData<BufferedImage> imageData, OpenCVFeatureCalculator calculator, 
    		double downsample) {
        setImageData(imageData);
        this.calculator = calculator;
        this.downsample = downsample;
    }

    public double getDownsample() {
        return downsample;
    }

    public void setFeatureCalculator(OpenCVFeatureCalculator calculator) {
        if (this.calculator == calculator)
            return;
        this.calculator = calculator;
        resetTrainingData();
    }

    public void setDownsample(double downsample) {
        if (this.downsample == downsample)
            return;
        this.downsample = downsample;
        resetTrainingData();
    }

    public OpenCVFeatureCalculator getFeatureCalculator() {
        return calculator;
    }

    public void setImageData(ImageData<BufferedImage> imageData) {
        if (this.imageData == imageData)
            return;
        if (this.imageData != null) {
            this.imageData.getHierarchy().removePathObjectListener(this);
        }
        this.imageData = imageData;
        for (Mat temp : cacheFeatures.values())
        	temp.release();
        cacheFeatures.clear();
        if (this.imageData != null) {
            this.imageData.getHierarchy().addPathObjectListener(this);
        }
        changes = true;
    }


    public static Map<PathClass, Collection<ROI>> getAnnotatedROIs(PathObjectHierarchy hierarchy) {
        List<PathObject> annotations = hierarchy.getObjects(null, PathAnnotationObject.class).stream().filter((it) -> {
            return it.getPathClass() != null && it.getPathClass() != PathClassFactory.getRegionClass() && it.hasROI();
        }).collect(Collectors.toList());

        Map<PathClass, Collection<ROI>> map = new TreeMap<>();
        for (PathObject it : annotations) {
            PathClass pathClass = it.getPathClass();
            if (map.containsKey(pathClass))
                map.get(pathClass).add(it.getROI());
            else {
            	// TODO: Check if this needs to be a set at all
            	Set<ROI> list = new LinkedHashSet<>();
            	list.add(it.getROI());
                map.put(pathClass, list);
            }
        }
        return map;
    }


    private Map<PathClass, Collection<ROI>> lastAnnotatedROIs;


    public Map<PathClass, Collection<ROI>> getLastTrainingROIs() {
        return lastAnnotatedROIs;
    }

    private Map<Integer, PathClass> pathClassesLabels = new LinkedHashMap<>();
    
    public synchronized Map<Integer, PathClass> getPathClassLabels() {
    	return Collections.unmodifiableMap(pathClassesLabels);
    }

    public synchronized boolean updateTrainingData() {
        if (imageData == null) {
            resetTrainingData();
            return false;
        }
        PathObjectHierarchy hierarchy = imageData.getHierarchy();

        Map<PathClass, Collection<ROI>> map = getAnnotatedROIs(hierarchy);

        // We need at least two classes for anything very meaningful to happen
        int nTargets = map.size();
        if (nTargets <= 1) {
            resetTrainingData();
            return false;
        }

        // Training is the same - so nothing else to do unless the varType changed
        if (map.equals(lastAnnotatedROIs)) {
       		return true;
        }

        // Get the current image
        ImageServer<BufferedImage> server = imageData.getServer();
 
        List<PathClass> pathClasses = new ArrayList<>(map.keySet());
        pathClassesLabels.clear();
        
        List<PixelClassifierOutputChannel> newChannels = new ArrayList<>();
        String path = imageData.getServerPath();
        List<Mat> allFeatures = new ArrayList<>();
        List<Mat> allTargets = new ArrayList<>();
        int label = 0;
        Set<PathClass> backgroundClasses = new HashSet<>(
        		Arrays.asList(
        				PathClassFactory.getDefaultPathClass(PathClassFactory.PathClasses.WHITESPACE),
        				PathClassFactory.getPathClass("Background")        				
        				)
        		);
        for (PathClass pathClass : pathClasses) {
            // Create a suitable channel
            Integer color = backgroundClasses.contains(pathClass) ?
            		PixelClassifierOutputChannel.TRANSPARENT : pathClass.getColor();
            PixelClassifierOutputChannel channel = new PixelClassifierOutputChannel(
                    pathClass.getName(), color);
            newChannels.add(channel);
            pathClassesLabels.put(label, pathClass);
            // Loop through the object & get masks
            for (ROI roi : map.get(pathClass)) {
                // Check if we've cached features
                // Here, we use the ROI regardless of classification - because we can quickly create a classification matrix
                Mat matFeatures = cacheFeatures.get(roi);
                if (matFeatures == null) {
                	
                	boolean isArea = roi instanceof PathArea;
                	boolean isLine = roi instanceof PathLine;
                	if (!isArea && !isLine) {
                		logger.warn("{} is neither an instance of PathArea nor PathLine! Will be skipped...", roi);
                		continue;
                	}
                    Shape shape = PathROIToolsAwt.getShape(roi);
                    
                    List<RegionRequest> requests = new ArrayList<>();
                    int tw = (int)Math.round(calculator.getMetadata().getInputWidth() * downsample);
                    int th = (int)Math.round(calculator.getMetadata().getInputHeight() * downsample);
                    for (int y = (int)roi.getBoundsY(); y < (int)Math.ceil(roi.getBoundsY() + roi.getBoundsHeight()); y += th) {
                        for (int x = (int)roi.getBoundsX(); x < (int)Math.ceil(roi.getBoundsX() + roi.getBoundsWidth()); x += tw) {
                        	requests.add(RegionRequest.createInstance(
                        			server.getPath(), downsample, x, y, tw, th, roi.getZ(), roi.getT()));
                        }                    	
                    }
                    
//                    List<RegionRequest> requests = ImageRegionStoreHelpers.getTilesToRequest(
//                			server, shape, downsample, roi.getZ(), roi.getT(),
//                			calculator.getMetadata().getInputWidth(),
//                			calculator.getMetadata().getInputHeight(), null);

                    matFeatures = new Mat();
                    List<Mat> rows = new ArrayList<>();
                    for (RegionRequest request : requests) {
                        // Get features & reshape so that each row has features for specific pixel
                        Mat matFeaturesFull;
						try {
							// TODO: FIX THE DOWNSAMPLE - IT IS LIKELY TO BE WRONG!
							matFeaturesFull = calculator.calculateFeatures(server, request);
						} catch (IOException e) {
							logger.warn("Unable to calculate features for " + request + " - will be skipped", e);
							continue;
						}

                        // Create a mask based on the output size after feature classification
                        // Note that the feature classification can incorporate additional resampling (e.g. with max pooling steps)
                        int resultWidth = matFeaturesFull.cols();
                        int resultHeight = matFeaturesFull.rows();
                        BufferedImage imgMask = new BufferedImage(resultWidth, resultHeight, BufferedImage.TYPE_BYTE_GRAY);
                        double downsampleMask = 0.5 * ((double)request.getWidth() / resultWidth) + 0.5 * ((double)request.getHeight() / resultHeight);
                        Graphics2D g2d = imgMask.createGraphics();
                        g2d.scale(1.0/downsampleMask, 1.0/downsampleMask);
                        g2d.translate(-request.getX(), -request.getY());
                        g2d.setColor(Color.WHITE);
                        if (isArea)
                        	g2d.fill(shape);
                        if (isLine) {
                        	g2d.setStroke(new BasicStroke((float)downsampleMask));
                        	g2d.draw(shape);
                        }
                        g2d.dispose();
                        
//                        if (pathClass.getName().equals("Tumor")) {
//                            new ImagePlus("Orig " + request.toString(), server.readBufferedImage(request)).show();
//                            new ImagePlus("Mask " + request.toString(), imgMask).show();
//                        }

//                        Mat matImage = OpenCVTools.imageToMat(img);
//                        matImage.convertTo(matImage, opencv_core.CV_32F);
                        Mat matMask = OpenCVTools.imageToMat(imgMask);
                        
                        int heightFeatures = matFeaturesFull.rows();
                        int widthFeatures = matFeaturesFull.cols();
                        if (heightFeatures != matMask.rows() || widthFeatures != matMask.cols()) {
                            opencv_imgproc.resize(matMask, matMask, new opencv_core.Size(widthFeatures, heightFeatures));
                        }
                        // Reshape mask to a column matrix
                        matMask.put(matMask.reshape(1, matMask.rows()*matMask.cols()));
//                        System.err.println('SIZE: ' + widthFeatures + ' x ' + heightFeatures)
//                        matFeaturesFull.convertTo(matFeaturesFull, opencv_core.CV_32F)
                        matFeaturesFull.put(matFeaturesFull.reshape(1, matMask.rows()*matMask.cols()));
                        // Extract the pixels
                        UByteIndexer indexerMask = matMask.createIndexer();
                        for (int r = 0; r < indexerMask.rows(); r++) {
                            if (indexerMask.get(r) == 0)
                                continue;
                            rows.add(matFeaturesFull.row(r));
                        }
                        indexerMask.release();
                    }
                    opencv_core.vconcat(new MatVector(rows.toArray(new Mat[0])), matFeatures);
                    
                    
                    cacheFeatures.put(roi, matFeatures);
                }
                if (matFeatures != null && !matFeatures.empty()) {
                    allFeatures.add(matFeatures.clone()); // Clone to be careful... not sure if normalization could impact this under adverse conditions
                    Mat targets = new Mat(matFeatures.rows(), 1, opencv_core.CV_32SC1, opencv_core.Scalar.all(label));
                    allTargets.add(targets);
                }
            }
            label++;
        }
        if (matTraining == null)
            matTraining = new Mat();
        if (matTargets == null)
            matTargets = new Mat();
        opencv_core.vconcat(new MatVector(allFeatures.toArray(new Mat[0])), matTraining);
        opencv_core.vconcat(new MatVector(allTargets.toArray(new Mat[0])), matTargets);

        
        opencv_core.patchNaNs(matTraining, 0.0);
        
        this.preprocessor = new OpenCVClassifiers.FeaturePreprocessor.Builder()
        	.normalize(Normalization.MEAN_VARIANCE)
//        	.pca(0.99, true)
        	.missingValue(0)
        	.buildAndApply(matTraining);
        

        logger.info("Training data: {} x {}, Target data: {} x {}", matTraining.rows(), matTraining.cols(), matTargets.rows(), matTargets.cols());
        
        if (channels == null)
            channels = new ArrayList<>();
        else
            channels.clear();
        channels.addAll(newChannels);

        lastAnnotatedROIs = Collections.unmodifiableMap(map);
        changes = false;
        return true;
    }


    public FeaturePreprocessor getLastFeaturePreprocessor() {
        return preprocessor;
    }
    
    
    private void resetTrainingData() {
        if (matTraining != null)
            matTraining.release();
        matTraining = null;
        if (matTargets != null)
            matTargets.release();
        for (Mat matTemp : cacheFeatures.values())
        	matTemp.release();
        cacheFeatures.clear();
        lastAnnotatedROIs = null;
        matTargets = null;
        changes = false;
    }


    public TrainData getTrainData() {
        if (changes)
            updateTrainingData();
        if (matTraining == null || matTargets == null)
            return null;
        return TrainData.create(matTraining, opencv_ml.ROW_SAMPLE, matTargets);
    }

    public List<PixelClassifierOutputChannel> getChannels() {
        return new ArrayList<>(channels);
    }

    @Override
    public void hierarchyChanged(PathObjectHierarchyEvent event) {
        if (event.isChanging())
            return;
        changes = true;
    }

}
