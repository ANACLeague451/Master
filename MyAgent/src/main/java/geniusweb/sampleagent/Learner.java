package geniusweb.sampleagent;

import geniusweb.actions.Offer;
import geniusweb.inform.Inform;
import geniusweb.issuevalue.Bid;
import geniusweb.party.DefaultParty;
import tudelft.utilities.logging.Reporter;

import java.util.ArrayList;
import java.util.logging.Level;

public class Learner {
    public Reporter reporter;

    private ArrayList<ArrayList<Offer>> info_pool;


    public Learner() {
        this.info_pool = new ArrayList<>();
    }

    protected void appendInfo(ArrayList<Offer> info) {
        this.info_pool.add(info);
        this.reporter.log(Level.INFO, info_pool.toString());
//        this.reporter.log(Level.INFO, this.info_pool.get(0).get(0).getActor().toString());
//        this.reporter.log(Level.INFO, Integer.toString(this.info_pool.get(0).size()));
    }

    protected void processEncounter() {
        // process data in learning phase

    }
}
