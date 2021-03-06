/*
 * SamplingFractionAnalysisDriver.java
 *
 * Created on May 19, 2008, 11:54 AM
 *
 * $Id: SamplingFractionAnalysisDriver.java,v 1.8 2010/05/28 18:34:00 ngraf Exp $
 */

package org.lcsim.cal.calib;

import Jama.Matrix;
import hep.aida.ITree;
import hep.physics.vec.Hep3Vector;
import java.util.List;
import org.lcsim.event.CalorimeterHit;
import org.lcsim.event.Cluster;
import org.lcsim.event.EventHeader;
import org.lcsim.event.MCParticle;
import org.lcsim.geometry.IDDecoder;
import org.lcsim.geometry.subdetector.CylindricalCalorimeter;
import org.lcsim.geometry.subdetector.CylindricalEndcapCalorimeter;
import org.lcsim.recon.cluster.fixedcone.FixedConeClusterer;
import org.lcsim.recon.cluster.fixedcone.FixedConeClusterer.FixedConeDistanceMetric;
import org.lcsim.util.Driver;
import org.lcsim.util.aida.AIDA;

import static java.lang.Math.abs;
import static java.lang.Math.sqrt;
import org.lcsim.conditions.ConditionsManager;
import org.lcsim.conditions.ConditionsManager.ConditionsSetNotFoundException;
import org.lcsim.conditions.ConditionsSet;

/**
 *
 * @author Norman Graf
 */
public class SamplingFractionAnalysisDriver extends Driver
{
    private ConditionsSet _cond;
    private CollectionManager _collectionmanager = CollectionManager.defaultInstance();
    // we will accumulate the raw energy values in three depths:
    // 1. Layers (0)1 through (20)21 of the EM calorimeter (note that if layer 0 is massless, SF==1.)
    // 2. last ten layers of the EM calorimeter
    // 3. the hadron calorimeter
    //
    private double[][] _acc = new double[3][3];
    private double[] _vec = new double[3];
    
    // let's use a clusterer to remove effects of calorimeter cells hit far, far away.
    // use the only cross-detector clusterer we have:
    private FixedConeClusterer _fcc;
    
    private AIDA aida = AIDA.defaultInstance();
    private ITree _tree;
    
    private boolean _initialized = false;
    private boolean _debug = false;
    
    
    // TODO fix this dependence on EM calorimeter geometry
    boolean skipFirstLayer = false;
    int firstEmStartLayer = 0;
    int secondEmStartLayer = 20;
    
    double emCalInnerRadius = 0.;
    double emCalInnerZ = 0.;
    
    /** Creates a new instance of SamplingFractionAnalysisDriver */
    public SamplingFractionAnalysisDriver()
    {
        _tree = aida.tree();
    }
    
    
    protected void process(EventHeader event)
    {
        super.process(event);
        //System.out.println("processing SamplingFractionAnalysisDriver");
        // TODO make these values runtime definable
        String[] det = {"EMBarrel","EMEndcap"};
//        String[] collNames = {"EcalBarrHits", "EcalEndcapHits", "HcalBarrHits", "HcalEndcapHits"};
//        double[] mipHistMaxBinVal = {.0005, .0005, .005, .005};
//        double timeCut = 100.; // cut on energy depositions coming more than 100 ns late
//        double ECalMipCut = .0001/3.; // determined from silicon using muons at normal incidence
//        double HCalMipCut = .0008/3.; // determined from scintillator using muons at normal incidence
//        double[] mipCut = {ECalMipCut, ECalMipCut, HCalMipCut, HCalMipCut};
        
        
        if(!_initialized)
        {
            ConditionsManager mgr = ConditionsManager.defaultInstance();
            try
            {
                _cond = mgr.getConditions("CalorimeterCalibration");
            }
            catch(ConditionsSetNotFoundException e)
            {
                System.out.println("ConditionSet CalorimeterCalibration not found for detector "+mgr.getDetector());
                System.out.println("Please check that this properties file exists for this detector ");
            }
            double radius = .5;
            double seed = 0.;//.1;
            double minE = .05; //.25;
            _fcc = new FixedConeClusterer(radius, seed, minE, FixedConeDistanceMetric.DPHIDTHETA);
            
            // detector geometries here...
            // barrel
            CylindricalCalorimeter calsubBarrel = (CylindricalCalorimeter)event.getDetector().getSubdetectors().get(det[0]);
            // TODO remove this hardcoded dependence on the first layer
            if(calsubBarrel.getLayering().getLayer(0).getSlices().get(0).isSensitive())
            {
                skipFirstLayer = true;
                firstEmStartLayer += 1;
                secondEmStartLayer += 1;
            }
//            Layering layering = calsubBarrel.getLayering();
//            for(int i=0; i<layering.size(); ++i)
//            {
//                Layer l = layering.getLayer(i);
//                System.out.println("layering "+i);
//                List<LayerSlice> slices = l.getSlices();
//                for(int j=0; j<slices.size(); ++j)
//                {
//                    LayerSlice slice = slices.get(j);
//                    System.out.println("Layer "+i+" slice "+j+" is "+ slice.getMaterial().getName() +" and "+(slice.isSensitive() ? " is sensitive" : ""));
//                }
//            }
            emCalInnerRadius = calsubBarrel.getInnerRadius();
            //endcap
            CylindricalEndcapCalorimeter calsubEndcap = (CylindricalEndcapCalorimeter)event.getDetector().getSubdetectors().get(det[1]);
            emCalInnerZ = abs(calsubEndcap.getZMin());
            if(skipFirstLayer) System.out.println("processing "+event.getDetectorName()+" with an em calorimeter with a massless first gap");
            System.out.println("Calorimeter bounds: r= "+emCalInnerRadius+ " z= "+emCalInnerZ);
            System.out.println("initialized...");
            
            _initialized = true;
        }
        
        // organize the histogram tree by species and energy
        List<MCParticle> mcparts = event.getMCParticles();
        MCParticle mcpart = mcparts.get(mcparts.size()-1);
        String particleType = mcpart.getType().getName();
        double mcEnergy = mcpart.getEnergy();
        long mcIntegerEnergy = Math.round(mcEnergy);
        boolean meV = false;
        if(mcEnergy<.99)
        {
            mcIntegerEnergy = Math.round(mcEnergy*1000);
            meV = true;
        }
        
        _tree.mkdirs(particleType);
        _tree.cd(particleType);
        _tree.mkdirs(mcIntegerEnergy+(meV ? "_MeV": "_GeV"));
        _tree.cd(mcIntegerEnergy+(meV ? "_MeV": "_GeV"));
        
        // this analysis is intended for single particle calorimeter response.
        // let's make sure that the primary particle did not interact in the
        // tracker...
        Hep3Vector endpoint = mcpart.getEndPoint();
        // this is just crap. Why not use SpacePoint?
        double radius = sqrt(endpoint.x()*endpoint.x()+endpoint.y()*endpoint.y());
        double z = endpoint.z();
//        System.out.println("Input MCParticle endpoint: r="+radius+" z= "+z);
        
        boolean doit = true;
        if(radius<emCalInnerRadius && abs(z) < emCalInnerZ) doit = false;
        if(doit)
        {
//            // now let's check the em calorimeters...
//            // get all of the calorimeter hits...
//            List<CalorimeterHit> allHits = new ArrayList<CalorimeterHit>();
//            // and the list after cuts.
//            List<CalorimeterHit> hitsToCluster = new ArrayList<CalorimeterHit>();
//            int i = 0;
//            for(String name : collNames)
//            {
////                System.out.println("fetching "+name+" from the event");
//                List<CalorimeterHit> hits = event.get(CalorimeterHit.class, name);
////                System.out.println(name+ " has "+hits.size()+" hits");
//                // let's look at the hits and see if we need to cut on energy or time...
//                for(CalorimeterHit hit: hits)
//                {
//                    aida.histogram1D(name+" raw calorimeter cell energy",100, 0., mipHistMaxBinVal[i]).fill(hit.getRawEnergy());
//                    aida.histogram1D(name+" raw calorimeter cell energy full range",100, 0., 0.2).fill(hit.getRawEnergy());
////                    aida.cloud1D(name+" raw calorimeter cell energies").fill(hit.getRawEnergy());
//                    aida.histogram1D(name+" calorimeter cell time",100,0., 200.).fill(hit.getTime());
//                    if(hit.getTime()<timeCut)
//                    {
//                        if(hit.getRawEnergy()>mipCut[i])
//                        {
//                            hitsToCluster.add(hit);
//                        }
//                    }
//                }
//                allHits.addAll(hits);
//                ++i;
//            }
//            System.out.println("ready to cluster "+hitsToCluster.size()+ " hits");
            String processedHitsName = _cond.getString("ProcessedHitsCollectionName");
            List<CalorimeterHit> hitsToCluster = _collectionmanager.getList(processedHitsName);//event.get(CalorimeterHit.class, processedHitsName);

            if(_debug) System.out.println("clustering "+hitsToCluster.size()+" hits");
            // quick check
//            for(CalorimeterHit hit : hitsToCluster)
//            {
//                System.out.println("hit ");
//                System.out.println(hit.getLCMetaData().getName());
//            }
            // cluster the hits
            List<Cluster> clusters = _fcc.createClusters(hitsToCluster);
            if(_debug) System.out.println("found "+clusters.size()+" clusters");
            aida.histogram1D("number of found clusters", 10, -0.5, 9.5).fill(clusters.size());
            for(Cluster c : clusters)
            {
//                System.out.println(c);
                aida.cloud1D("cluster energy for all clusters").fill(c.getEnergy());
            }
            
            // proceed only if we found a single cluster above threshold
            // too restrictive! simply take the highest energy cluster
            if(clusters.size()>0)
            {
                Cluster c = clusters.get(0);
                
                aida.cloud1D("Highest energy cluster energy").fill(c.getEnergy());
                aida.cloud1D("Highest energy cluster number of cells").fill(c.getCalorimeterHits().size());
                
                double clusterEnergy = c.getEnergy();
                double mcMass = mcpart.getType().getMass();
                // subtract the mass to get kinetic energy...
                double expectedEnergy = sqrt(mcEnergy*mcEnergy - mcMass*mcMass);
//                System.out.println(mcpart.getType().getName()+" "+expectedEnergy);
                aida.cloud1D("measured - predicted energy").fill(clusterEnergy - expectedEnergy);
                
                // let's now break down the cluster by component.
                // this analysis uses:
                // 1.) first 20 EM layers
                // 2.) next 10 EM layers
                // 3.) Had layers
                List<CalorimeterHit> hits = c.getCalorimeterHits();
                double[] vals = new double[3];
                double clusterRawEnergy = 0.;
                for(CalorimeterHit hit : hits)
                {
                    long id = hit.getCellID();
                    IDDecoder decoder = hit.getIDDecoder();
                    decoder.setID(id);
                    int layer = decoder.getLayer();
                    String detectorName = decoder.getSubdetector().getName();
                    int type = 0;
                    if(detectorName.startsWith("EM"))
                    {
                        if(layer>=firstEmStartLayer && layer<secondEmStartLayer)
                        {
                            type = 0;
                        }
                        else
                        {
                            type = 1;
                        }
                    }
                    if(detectorName.startsWith("HAD"))
                    {
                        type = 2;
                    }
                    clusterRawEnergy += hit.getRawEnergy();
                    vals[type] += hit.getRawEnergy();
                } // end of loop over hits in cluster
                // set up linear least squares:
                // expectedEnergy = a*E1 + b*E2 +c*E3
                for(int j=0; j<3; ++j)
                {
                    _vec[j] += expectedEnergy*vals[j];
                    for(int k=0; k<3; ++k)
                    {
                        _acc[j][k] += vals[j]*vals[k];
                    }
                }
            } // end of single cluster cut
            
//            event.put("All Calorimeter Hits",allHits);
//            event.put("Hits To Cluster",hitsToCluster);
            event.put("Found Clusters",clusters);
        }// end of check on decays outside tracker volume
        _tree.cd("/");
    }
    
    protected void endOfData()
    {
        System.out.println("done! endOfData.");
        // calculate the sampling fractions...
        Matrix A = new Matrix(_acc, 3, 3);
        A.print(6,4);
        Matrix b = new Matrix(3,1);
        for(int i=0; i<3; ++i)
        {
            b.set(i,0,_vec[i]);
        }
        b.print(6,4);
        try
        {
            Matrix x = A.solve(b);
            x.print(6, 4);
            System.out.println("SamplingFractions: "+ (skipFirstLayer ? "1., ":"")+1./x.get(0,0)+ ", " + 1./x.get(1,0)+", "+1./x.get(2,0));
        }
        catch(Exception e)
        {
            e.printStackTrace();
            System.out.println("try reducing dimensionality...");
            Matrix Ap = new Matrix(_acc, 2, 2);
            Ap.print(6,4);
            Matrix bp = new Matrix(2,1);
            for(int i=0; i<2; ++i)
            {
                bp.set(i,0,_vec[i]);
            }
            bp.print(6,4);
            try
            {
                Matrix x = Ap.solve(bp);
                x.print(6, 4);
            }
            catch(Exception e2)
            {
                e2.printStackTrace();
            }
        }
    }
}