package com.petpet.c3po.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.petpet.c3po.adaptor.fits.FITSAdaptor;
import com.petpet.c3po.adaptor.rules.EmptyValueProcessingRule;
import com.petpet.c3po.adaptor.rules.FormatVersionResolutionRule;
import com.petpet.c3po.adaptor.rules.HtmlInfoProcessingRule;
import com.petpet.c3po.adaptor.tika.TIKAAdaptor;
import com.petpet.c3po.api.adaptor.AbstractAdaptor;
import com.petpet.c3po.api.adaptor.ProcessingRule;
import com.petpet.c3po.api.dao.PersistenceLayer;
import com.petpet.c3po.api.model.Element;
import com.petpet.c3po.api.model.helper.MetadataStream;
import com.petpet.c3po.common.Constants;
import com.petpet.c3po.gatherer.LocalFileGatherer;
import com.petpet.c3po.utils.exceptions.C3POConfigurationException;

//TODO generalize the gatherer with the interface.
public class Controller {

  private static final Logger LOGGER = LoggerFactory.getLogger(Controller.class);
  private PersistenceLayer persistence;
  private ExecutorService adaptorPool;
  private ExecutorService consolidatorPool;
  private LocalFileGatherer gatherer;
  private final Queue<Element> processingQueue;
  private int counter = 0;

  public Controller(PersistenceLayer pLayer) {
    this.persistence = pLayer;
    this.processingQueue = new LinkedList<Element>();
  }

  public void collect(Map<String, String> config) throws C3POConfigurationException {

    this.checkConfiguration(config);

    this.gatherer = new LocalFileGatherer(config);

    int threads = Integer.parseInt(config.get(Constants.CNF_THREAD_COUNT));
    String type = (String) config.get(Constants.CNF_INPUT_TYPE);
    Map<String, String> adaptorcnf = this.getAdaptorConfig(config);

    // LOGGER.info("{} files to be processed for collection {}",
    // gatherer.getCount(),
    // config.get(Constants.CNF_COLLECTION_NAME));

    this.startJobs(type, threads, adaptorcnf);

  }

  /**
   * Checks the config passed to this controller for required values.
   * 
   * @param config
   * @throws C3POConfigurationException
   */
  private void checkConfiguration(final Map<String, String> config) throws C3POConfigurationException {
    String inputType = config.get(Constants.CNF_INPUT_TYPE);
    if (inputType == null || (!inputType.equals("TIKA") && !inputType.equals("FITS"))) {
      throw new C3POConfigurationException("No input type specified. Please use one of FITS or TIKA.");
    }

    String path = config.get(Constants.CNF_COLLECTION_LOCATION);
    if (path == null) {
      throw new C3POConfigurationException("No input file path provided. Please provide a path to the input files.");
    }

    String name = config.get(Constants.CNF_COLLECTION_NAME);
    if (name == null || name.equals("")) {
      throw new C3POConfigurationException("The name of the collection is not set. Please set a name.");
    }
  }

  private Map<String, String> getAdaptorConfig(Map<String, String> config) {
    final Map<String, String> adaptorcnf = new HashMap<String, String>();
    for (String key : config.keySet()) {
      if (key.startsWith("adaptor.")) {
        adaptorcnf.put(key, config.get(key));
      }
    }

    adaptorcnf.put(Constants.CNF_COLLECTION_ID, config.get(Constants.CNF_COLLECTION_NAME));
    return adaptorcnf;
  }

  private void startJobs(String type, int threads, Map<String, String> adaptorcnf) {

    // LocalFileGatherer gatherer = new LocalFileGatherer();

    this.adaptorPool = Executors.newFixedThreadPool(threads);
    this.consolidatorPool = Executors.newFixedThreadPool(4); // TODO change this

    List<Consolidator> consolidators = new ArrayList<Consolidator>();

    for (int i = 0; i < 4; i++) {
      Consolidator c = new Consolidator(processingQueue);
      consolidators.add(c);
      this.consolidatorPool.submit(c);
    }
    System.out.println("added consolidator workers");
    // no more consolidators can be added.
    this.consolidatorPool.shutdown();

    System.out.println("shut down consoldator pool ");

    List<ProcessingRule> rules = this.getRules();

    for (int i = 0; i < threads; i++) {
      // final AbstractAdaptor f = this.getAdaptor(type);
      final AbstractAdaptor a = this.getAdaptor(type);
      // TODO set gatherer.
      a.setCache(persistence.getCache());
      a.setQueue(processingQueue);
      a.setGatherer(gatherer);
      a.setConfig(adaptorcnf);
      a.setRules(rules);
      a.configure();

      this.adaptorPool.submit(a);
    }
    System.out.println("added adaptor workers");

    this.adaptorPool.shutdown();

    System.out.println("shutdown adaptor pool");

    this.gatherer.start();

    try {

      // kills the pool and all workers after a month;
      boolean adaptorsTerminated = this.adaptorPool.awaitTermination(2678400, TimeUnit.SECONDS);

      if (adaptorsTerminated) {
        for (Consolidator c : consolidators) {
          c.setRunning(false);
        }

        System.out.println("Stopped consolidators");

        synchronized (processingQueue) {
          this.processingQueue.notifyAll();
        }

        this.consolidatorPool.awaitTermination(2678400, TimeUnit.SECONDS);

        // String collection = (String)
        // adaptorcnf.get(Constants.CNF_COLLECTION_ID);
        // ActionLog log = new ActionLog(collection, ActionLog.UPDATED_ACTION);
        // new ActionLogHelper(this.persistence).recordAction(log);

      } else {
        LOGGER.error("Time out occurred, process was terminated");
      }

    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  public PersistenceLayer getPersistence() {
    return this.persistence;
  }

  @Deprecated
  public synchronized MetadataStream getNext() {
    List<MetadataStream> next = this.gatherer.getNext(1);
    MetadataStream result = null;

    if (!next.isEmpty()) {
      result = next.get(0);
    }

    this.counter++;

    if (counter % 1000 == 0) {
      LOGGER.info("Finished processing {} files", counter);
    }

    return result;
  }

  // TODO this should be generated via some user input.
  private List<ProcessingRule> getRules() {
    List<ProcessingRule> rules = new ArrayList<ProcessingRule>();
    rules.add(new HtmlInfoProcessingRule());
    rules.add(new EmptyValueProcessingRule());
    rules.add(new FormatVersionResolutionRule());
    //TODO add collection name rule
    return rules;
  }

  private AbstractAdaptor getAdaptor(String type) {
    if (type.equals("FITS")) {
      return new FITSAdaptor();
    } else if (type.equals("TIKA")) {
      return new TIKAAdaptor();
    } else {
      return null;
    }
  }

}
