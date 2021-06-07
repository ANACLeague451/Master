package geniusweb.sampleagent;

import geniusweb.actions.Accept;
import geniusweb.actions.Action;
import geniusweb.actions.Offer;
import geniusweb.actions.PartyId;
import geniusweb.bidspace.AllBidsList;
import geniusweb.issuevalue.Bid;
import geniusweb.issuevalue.Domain;
import geniusweb.issuevalue.Value;
import geniusweb.party.Capabilities;
import geniusweb.party.DefaultParty;
import geniusweb.inform.ActionDone;
import geniusweb.inform.Finished;
import geniusweb.inform.Inform;
import geniusweb.inform.Settings;
import geniusweb.inform.YourTurn;
import geniusweb.profile.Profile;
import geniusweb.profile.utilityspace.UtilitySpace;
import geniusweb.profileconnection.ProfileConnectionFactory;
import geniusweb.profileconnection.ProfileInterface;
import geniusweb.progress.Progress;
import geniusweb.progress.ProgressRounds;
import tudelft.utilities.logging.Reporter;

import javax.websocket.DeploymentException;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.logging.Level;

/*
 * Implementation of BOA components of a SAOP party that can handle only bilateral
 * negotiations (1 other party).
 */

public class MyAgent extends DefaultParty {

    // ID of our agent
    private PartyId partyId;

    private Domain domain;
    private AllBidsList allBidsList;

    protected ProfileInterface profileInterface;
    private Profile profile;
    // Utility value of all possible bids according to our profile
    private HashMap<Bid, BigDecimal> bidsUtilityMap = new HashMap<>();

    private Progress progress;
    // Current time in the negotiation
    private double time = 0.0;

    // Last received bid from the opponent
    private Bid lastReceivedBid = null;
    // History of the received offers during the negotiation session
    private List<Bid> receivedOffers = new ArrayList<>();

    private final Random random = new Random();
    // Minimum utility value of a bid that the agent offers or accepts.
    private double acceptableUtilityValue = 1.0;

    // [66,70], [134,163], [191,192], [268-

    private ArrayList<Object[]> issueWeights = new ArrayList<>();
    private ArrayList<Object[]> issueValueList = new ArrayList<>();

    private static final int kValue = 3;

    private boolean concession = false;

    private List<Bid> opponentBids = null;

    private double utility = 0.0;
    private ArrayList<Double> opponentUtilities = new ArrayList<Double>();


    public MyAgent() {

    }

    public MyAgent(Reporter reporter) {
        // Reporter is used for debugging
        super(reporter);
    }

    @Override
    public void notifyChange(Inform info) {
        try {
            if (info instanceof Settings) {
                Settings settings = (Settings) info;
                init(settings);
            } else if (info instanceof ActionDone) {
                Action action = ((ActionDone) info).getAction();
                if (action instanceof Offer) {
                    this.lastReceivedBid = ((Offer) action).getBid();
                }
            } else if (info instanceof YourTurn) {
                if (progress instanceof ProgressRounds) {
                    progress = ((ProgressRounds) progress).advance();
                }
                myTurn();
            } else if (info instanceof Finished) {
                getReporter().log(Level.INFO, "Final outcome:" + info);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to handle info", e);
        }
    }

    @Override
    public Capabilities getCapabilities() {
        return new Capabilities(new HashSet<>(Arrays.asList("SAOP", "Learn")), Collections.singleton(Profile.class));
    }

    @Override
    public String getDescription() {
        return "MyAgent offers bids having utility value greater than acceptableUtilityValue which is " +
                "a time dependent variable. Before sending the selected bid, it replaces the value of a random issue " +
                "with the issue value of the randomly selected bid from the history of the offered bids.";
    }

    // Called at the beginning of the negotiation session
    private void init(Settings settings) throws IOException, DeploymentException {
        this.profileInterface = ProfileConnectionFactory.create(settings.getProfile().getURI(), getReporter());
        this.partyId = settings.getID();
        this.progress = settings.getProgress();
        try {
            this.profile = this.profileInterface.getProfile();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        this.domain = this.profile.getDomain();
        this.allBidsList = new AllBidsList(domain);
        for (Bid bid : this.allBidsList) {
            this.bidsUtilityMap.put(bid, ((UtilitySpace) this.profile).getUtility(bid));
        }
        this.bidsUtilityMap = sortBidsByUtility(this.bidsUtilityMap);

        double initialIssueWeight = (double) 1 / this.domain.getIssues().toArray().length;
        for (int i = 0; i < this.domain.getIssues().toArray().length; i++) {
            this.issueWeights.add(new Object[]{this.domain.getIssues().toArray()[i].toString(), initialIssueWeight});
        }

        Object[] issueValue = new Object[3];

        for (int i = 0; i < this.domain.getIssues().size(); i++) {
            for (int j = 0; j < this.domain.getValues((String) this.domain.getIssues().toArray()[i]).size().intValue(); j++) {
                issueValue = new Object[]{this.domain.getIssues().toArray()[i].toString(),
                        this.domain.getValues((String) this.domain.getIssues().toArray()[i]).get(j),
                        (double) 1 / this.domain.getValues((String) this.domain.getIssues().toArray()[i]).size().intValue()};
                this.issueValueList.add(issueValue);
            }
        }
    }

    // Sorting the bidsUtilityMap according to their utility value (ascending order)
    private LinkedHashMap<Bid, BigDecimal> sortBidsByUtility(HashMap<Bid, BigDecimal> utilityMap) {
        List<Map.Entry<Bid, BigDecimal>> list = new LinkedList<>(utilityMap.entrySet());
        Collections.sort(list, Collections.reverseOrder(Comparator.comparing(Map.Entry::getValue)));
        LinkedHashMap<Bid, BigDecimal> sortedHashMap = new LinkedHashMap<>();
        for (Map.Entry<Bid, BigDecimal> tuple : list) {
            sortedHashMap.put(tuple.getKey(), tuple.getValue());
        }
        return sortedHashMap;
    }

    //This function is called when it's our turn so that we can take an action.
    private void myTurn() throws IOException {
        // Logging the process
        getReporter().log(Level.INFO, "<MyAgent>: It's my turn!");
        // Increasing the round count
        this.time = progress.get(System.currentTimeMillis());
        this.acceptableUtilityValue = 0.7 + (1 - this.time) * 0.3;
        getReporter().log(Level.INFO, "Time:" + this.time);
        getReporter().log(Level.INFO, "Acceptable Utility Value:" + this.acceptableUtilityValue);

        opponentUtilities.clear();
        for (Bid p : this.bidsUtilityMap.keySet())
            opponentUtilities.add(random.nextDouble());


        int currentRound = 0;
        if (progress instanceof ProgressRounds) {
            currentRound = ((ProgressRounds) progress).getCurrentRound();
        }
        // First round: lastReceivedBid == null
        if (lastReceivedBid != null) {
            this.receivedOffers.add(this.lastReceivedBid);
            getReporter().log(Level.INFO, "Received Bid:" + lastReceivedBid.toString());
            //if (currentRound > 3)
            valueEstimation();

            //issueWeightEstimation();

        }

        getReporter().log(Level.INFO, "OUR UTILITIES:" + this.bidsUtilityMap.values());
        Bid nextBid = createBid();
        updateAcceptable(nextBid);

        Action action = null;
        if (isAcceptable(lastReceivedBid)) {
            // Action of acceptance
            action = new Accept(partyId, lastReceivedBid);
            getReporter().log(Level.INFO, "<MyAgent>: I accept the offer.");
        } else {
            action = makeAnOffer(nextBid);
        }
        getConnection().send(action);

    }

    private boolean isAcceptable(Bid bid) {
        // First round: lastReceivedBid == null
        if (bid == null)
            return false;
        // Returns true if utility value of the bid is greater than acceptable value
        return ((UtilitySpace) this.profile).getUtility(bid).doubleValue() > this.acceptableUtilityValue;
    }

    private Bid createBid() {

        Bid offeredBid = (Bid) this.bidsUtilityMap.keySet().toArray()[0];
        int totalRounds = 0;
        int currentRound = 0;

        if (progress instanceof ProgressRounds) {
            totalRounds = ((ProgressRounds) progress).getTotalRounds();
            currentRound = ((ProgressRounds) progress).getCurrentRound();
        }
        System.out.println("BOI" + currentRound);

        if (currentRound == 1 && lastReceivedBid == null)
            return offeredBid;

        else if (currentRound <= 6)
            offeredBid = (Bid) this.bidsUtilityMap.keySet().toArray()[currentRound];

        else {
            getReporter().log(Level.INFO, "I AM INNNN");
            ArrayList<Bid> temp = new ArrayList<Bid>();
            for (Object tempBid : this.bidsUtilityMap.keySet().toArray()) {
                temp.add((Bid) tempBid);
            }
            List<Bid> paretos = this.getParetoPoints(temp);
            Bid nashPoint = calculateNashPoint(temp, paretos);
            getReporter().log(Level.INFO, "Nash:" + nashPoint);
            offeredBid = nashPoint;
        }
        /*else {
            maxAcceptableValue = maxAcceptableValue - 0.02;
            minAcceptableValue = 3 * maxAcceptableValue / 4;
            getReporter().log(Level.INFO, "Min Accep: " + minAcceptableValue);
            getReporter().log(Level.INFO, "Max Accep: " + maxAcceptableValue);
            double averageAcceptatableValue = (maxAcceptableValue + minAcceptableValue) / 2;
            for(int i=0; i<bidsUtilityMap.size(); i++){
                BigDecimal bd = (BigDecimal)bidsUtilityMap.values().toArray()[i];
                double a = bd.doubleValue();
                if(a < averageAcceptatableValue && i != 0)
                    offeredBid = (Bid) this.bidsUtilityMap.keySet().toArray()[i-1];
            }
        }*/
        return offeredBid;
    }

    private Offer makeAnOffer(Bid offeredBid) {
        getReporter().log(Level.INFO, "<MyAgent>: I am offering bid: " + offeredBid);
        // Returns an offering action with the bid selected
        return new Offer(partyId, offeredBid);
    }


    private void updateAcceptable(Bid nextBid) {
        double upper = 0.9, lower = 0.7;
        double bidUtil = ((UtilitySpace) profile).getUtility(nextBid).doubleValue();
        if (bidUtil >= upper) {
            this.acceptableUtilityValue = upper;
        } else if (bidUtil <= lower) {
            this.acceptableUtilityValue = lower;
        } else {
            int total = 1, current = 0;
            if (progress instanceof ProgressRounds) {
                total = ((ProgressRounds) progress).getTotalRounds();
                current = ((ProgressRounds) progress).getCurrentRound();
            }
            this.acceptableUtilityValue = bidUtil - (bidUtil - lower) * current / total;
        }
    }

    private Bid calculateNashPoint(List<Bid> points, List<Bid> paretos) {

        Bid nashPoint = null;
        double maximumUtility = 0.0;

        for (int i = 0; i < points.size(); ++i) {
            if (!paretos.contains(points.get(i)))
                continue; // not nash for sure
            double prd = ((BigDecimal) bidsUtilityMap.values().toArray()[i]).doubleValue() * opponentUtilities.get(i);
            if (prd > maximumUtility) {
                nashPoint = points.get(i);
                maximumUtility = prd;
            }
        }
        return nashPoint;
    }

    private List<Bid> getParetoPoints(List<Bid> points) {
        ArrayList<Bid> paretoPoints = new ArrayList<Bid>();
        ArrayList<Bid> dominatedList = new ArrayList<Bid>();
        paretoPoints.add(points.get(0));
        boolean pareto = false;

        for (int i = 1; i < points.size(); i++) {
            double ourCurrentBidValue = ((BigDecimal) bidsUtilityMap.values().toArray()[i]).doubleValue();
            double opponentCurrentBidValue = opponentUtilities.get(i);
            for (int j = 0; j < i; ++j) {
                if (!paretoPoints.contains(points.get(j)))
                    continue;
                double ourPreviousBidValue = ((BigDecimal) bidsUtilityMap.values().toArray()[j]).doubleValue();
                double opponentPreviousBidValue = opponentUtilities.get(j);
                if (ourCurrentBidValue <= ourPreviousBidValue && opponentCurrentBidValue <= opponentPreviousBidValue)
                    break; // dominated
                if (ourCurrentBidValue >= ourPreviousBidValue || opponentCurrentBidValue >= opponentPreviousBidValue) {
                    pareto = true;
                    if (ourCurrentBidValue >= ourPreviousBidValue && opponentCurrentBidValue >= opponentPreviousBidValue) {
                        dominatedList.add(points.get(j));
                    }
                }
            }
            if (pareto) {
                paretoPoints.add(points.get(i));
                for (Bid dominated : dominatedList)
                    paretoPoints.remove(dominated); // remove previously added but dominated paretos
            }
            pareto = false;
            dominatedList.clear();

        }

        return paretoPoints;
    }
    //private void issueWeightEstimation() {
        /*Bid[] previousWindow = new Bid[kValue];
        Bid[] currentWindow = new Bid[kValue];

        if (this.receivedOffers.size() / kValue > 1 && this.receivedOffers.size() % kValue == 0) {
            for (int i = 0; i < 3; i++) {
                previousWindow[i] = this.receivedOffers.get(((this.receivedOffers.size() / 3) - 2) * 3 + i);
                currentWindow[i] = this.receivedOffers.get(((this.receivedOffers.size() / 3) - 1) * 3 + i);
            }
        }

        /*System.out.println("PREV CURR");
        for(int i = 0; i < kValue; i++){
            System.out.println("PREV " + previousWindow[i]);
            System.out.println("CURR " + currentWindow[i]);
        }

        this.concession = false;
        Set<String> issuesSet = new HashSet<String>();
        Set<String> leftoverIssuesSet = new HashSet<String>();

        Set<String> allIssuesSet = new HashSet<String>();
        for (int i = 0; i < this.domain.getIssues().size(); i++) {
            allIssuesSet.add((String) this.domain.getIssues().toArray()[i]);
        }

        ArrayList<Object[]> currentIssueWeightList = new ArrayList<>(this.issueWeights);

        ArrayList<Object[]> prevFiValues = new ArrayList<>();

        Object[] prevFiValue;
        for (int i = 0; i < this.issueWeights.size(); i++) {
            for (int j = 0; j < this.domain.getValues((String) this.issueWeights.get(i)[0]).size().intValue(); j++) {
                prevFiValue = new Object[]{this.domain.getIssues().toArray()[i],
                        Fr(this.domain.getIssues().toArray()[i].toString(),
                                this.domain.getValues((String) this.issueWeights.get(i)[0]).get(j),
                                previousWindow)};
                prevFiValues.add(prevFiValue);
            }
        }
        /*System.out.println("PREVFI");
        for(int i = 0; i < prevFiValues.size(); i++){
            System.out.println(Arrays.toString(prevFiValues.get(i)));
        }

        ArrayList<Object[]> currFiValues = new ArrayList<>();

        Object[] currFiValue;
        for (int i = 0; i < this.issueWeights.size(); i++) {
            for (int j = 0; j < this.domain.getValues((String) this.issueWeights.get(i)[0]).size().intValue(); j++) {
                currFiValue = new Object[]{this.domain.getIssues().toArray()[i],
                        Fr(this.domain.getIssues().toArray()[i].toString(),
                                this.domain.getValues((String) this.issueWeights.get(i)[0]).get(j),
                                currentWindow)};
                currFiValues.add(currFiValue);
            }
        }*/

        /*System.out.println("CURRFI");
        for(int i = 0; i < currFiValues.size(); i++){
            System.out.println(Arrays.toString(currFiValues.get(i)));
        }

        for(int i = 0; i < this.issueValueList.size(); i++){
            System.out.println(Arrays.toString(this.issueValueList.get(i)));
        }

        double oldExpectedUtil = 0;
        double newExpectedUtil = 0;

        ArrayList<Object[]> chivalues = chiSquaredTest(prevFiValues, currFiValues);
        for (Object[] chivalue : chivalues) {
            if ((double) chivalue[1] > 0.05) {
                issuesSet.add((String) chivalue[0]);
            } else {
                allIssuesSet.removeAll(issuesSet);
                leftoverIssuesSet = allIssuesSet;
                for (int i = 0; i < kValue; i++) {
                    for (int k = 0; k < leftoverIssuesSet.size(); k++) {
                        for (int j = 0; j < this.issueValueList.size(); j++) {
                            Value val = (Value) this.issueValueList.get(j)[1];
                            if (val.equals(previousWindow[i].getValue((String)leftoverIssuesSet.toArray()[k]))){
                                oldExpectedUtil += (double)this.issueValueList.get(j)[2] * (double)prevFiValues.get(i)[1];
                            }
                            if (val.equals(previousWindow[i].getValue((String)this.domain.getIssues().toArray()[k]))){
                                newExpectedUtil += (double)this.issueValueList.get(j)[2] * (double)currFiValues.get(i)[1];
                            }
                        }
                    }
                }
                /*System.out.println(oldExpectedUtil);
                System.out.println(newExpectedUtil);
                if (newExpectedUtil < oldExpectedUtil) {
                    concession = true;
                }
            }
        }

        if((issuesSet.size() != this.domain.getIssues().size()) && concession){
            for(int i = 0; i < this.issueWeights.size(); i++){
                for(int j = 0; j < leftoverIssuesSet.size(); j++){
                    /*System.out.println(this.issueWeights.get(i)[0]);
                    System.out.println(leftoverIssuesSet.toArray()[j]);
                    System.out.println("  h         ");
                    if((this.issueWeights.get(i)[0]).equals(leftoverIssuesSet.toArray()[j])){
                        double oldValue = (double)this.issueWeights.get(i)[1];
                        this.issueWeights.remove(i);
                        this.issueWeights.add(i, new Object[]{leftoverIssuesSet.toArray()[j], oldValue + 10});
                        //System.out.println(this.issueWeights.get(i)[1]);
                    }
                }
            }
        }

    }*/

    private double Fr(String issue, Value value, Bid[] window) {
        int valueCount = 0;
        for (int i = 0; i < kValue; i++) {
            if (ifValueAppears(issue, value, window[i]) == 1) {
                valueCount++;
            }
        }
        double temp = 1.0 + valueCount;
        return (temp / kValue);
    }

    private ArrayList<Object[]> chiSquaredTest(ArrayList<Object[]> prevFi, ArrayList<Object[]> currFi) {
        ArrayList<Object[]> chiValues = new ArrayList<>();
        Object[] chiValue;
        double chi = 0;
        int index = 0;

        String[] issues = new String[this.domain.getIssues().size()];
        for (int i = 0; i < this.domain.getIssues().size(); i++) {
            issues[i] = this.domain.getIssues().toArray()[i].toString();
            //System.out.println(issues[i]);
        }

        while (index < issues.length) {
            for (int i = 0; i < prevFi.size(); i++) {
                if (issues[index].equals(prevFi.get(i)[0])) {
                    chi += Math.pow(((double) prevFi.get(i)[1] - (double) currFi.get(i)[1]), 2) / (double) currFi.get(i)[1];
                }
            }
            chiValue = new Object[]{issues[index], chi};
            chiValues.add(chiValue);
            chi = 0;
            index++;
        }

        return chiValues;
    }

    private void valueEstimation() {

        /*for(int i = 0; i < this.receivedOffers.size();i++){
            System.out.println(this.receivedOffers.get(i));
        }
        System.out.println("OFFER");*/

        double max = 0;
        for (int i = 0; i < this.issueValueList.size(); i++) {
            //System.out.println("OLD VALUE " + Arrays.toString(this.issueValueList.get(i)));
            double val = numeratorCalc((String) this.issueValueList.get(i)[0], (Value) this.issueValueList.get(i)[1]);  //Calculating numerator of division
            if (val > max) {
                max = val;
            }
            this.issueValueList.set(i, new Object[]{this.issueValueList.get(i)[0],
                    this.issueValueList.get(i)[1],
                    val});
            //System.out.println("NEW VALUE BEFORE DIVISION " + Arrays.toString(this.issueValueList.get(i)));
        }
        //System.out.println("MAX " + max);
        for (int i = 0; i < this.issueValueList.size(); i++) {
            this.issueValueList.set(i, new Object[]{this.issueValueList.get(i)[0],
                    this.issueValueList.get(i)[1],
                    (double) this.issueValueList.get(i)[2] / max});  //Dividing with max value
            //System.out.println("NEW VALUES" + Arrays.toString(this.issueValueList.get(i)));
        }

        int currentRound = 0;
        if (progress instanceof ProgressRounds) {
            currentRound = ((ProgressRounds) progress).getCurrentRound();
        }
        getReporter().log(Level.INFO, "------CURRENT ROUND------" + currentRound);

        for (int i = 0; i < this.issueValueList.size(); i++) {
            getReporter().log(Level.INFO, "Issue " + this.issueValueList.get(i)[0] +
                    " Value " + this.issueValueList.get(i)[1]
                    + " Weight " + this.issueValueList.get(i)[2]);
        }


    }


    private int ifValueAppears(String issue, Value value, Bid bid) {
        if (bid != null) {
            return bid.getValue(issue).equals(value) ? 1 : 0;
        } else
            //getReporter().log(Level.INFO, "HELLO I AM IN IFVALUEAPPEARS");
            //getReporter().log(Level.INFO, "BIDBIDBIDBID" + bid.toString());
            return 0;
    }

    private double numeratorCalc(String issue, Value value) {
        int count = 0;
        double exp = 0.2;

        for (int i = 0; i < this.receivedOffers.size(); i++) {
            if (ifValueAppears(issue, value, this.receivedOffers.get(i)) == 1)
                count++;
        }
        return Math.pow((count + 1), exp);
    }
}