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

    private HashMap<String, Value[]> issueList = new HashMap<>();
    private HashMap<String, Double> issueWeights = new HashMap<>();
    private ArrayList<Object[]> issueValueList = new ArrayList<>();

    private static final int kValue = 3;
    //private static double maxValue = 0;

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

        for (int i = 0; i < this.domain.getIssues().size(); i++) {
            Value[] valueList = new Value[this.domain.getValues((String) this.domain.getIssues().toArray()[i]).size().intValue()];
            for (int j = 0; j < this.domain.getValues((String) this.domain.getIssues().toArray()[i]).size().intValue(); j++) {
                valueList[j] = this.domain.getValues((String) this.domain.getIssues().toArray()[i]).get(j);
            }
            this.issueList.put(this.domain.getIssues().toArray()[i].toString(), valueList);
        }

        double initialIssueWeight = (double) 1 / this.domain.getIssues().toArray().length;
        for (int i = 0; i < this.domain.getIssues().toArray().length; i++) {
            issueWeights.put(this.domain.getIssues().toArray()[i].toString(), initialIssueWeight);
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

        Random rand = new Random();

        for (int i = 0; i < this.allBidsList.size().intValue() - 5; i++) {
            int random = rand.nextInt(this.allBidsList.size().intValue() - 3);
            this.receivedOffers.add(this.allBidsList.get(random));
        }
    }

    // Sorting the bidsUtilityMap according to their utility value (ascending order)
    private LinkedHashMap<Bid, BigDecimal> sortBidsByUtility(HashMap<Bid, BigDecimal> utilityMap) {
        List<Map.Entry<Bid, BigDecimal>> list = new LinkedList<>(utilityMap.entrySet());
        Collections.sort(list, Comparator.comparing(Map.Entry::getValue));
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

        // First round: lastReceivedBid == null
        if (lastReceivedBid != null) {
            this.receivedOffers.add(this.lastReceivedBid);
            getReporter().log(Level.INFO, "Received Bid:" + lastReceivedBid.toString());
            opponentPreferenceProfile(lastReceivedBid);
            valueEstimation(lastReceivedBid);
        }

        Action action = null;
        if (isAcceptable(lastReceivedBid)) {
            // Action of acceptance
            action = new Accept(partyId, lastReceivedBid);
            getReporter().log(Level.INFO, "<MyAgent>: I accept the offer.");
        } else {
            action = makeAnOffer();
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

    private Offer makeAnOffer() {
        Bid offeredBid;
        // Getting the list of the issues defined in the domain of the session
        Set<String> issuesList = this.domain.getIssues();
        List<Bid> acceptableBids = new ArrayList();
        for (Bid bid : this.allBidsList) {
            double bidUtility = ((UtilitySpace) this.profile).getUtility(bid).doubleValue();
            if (bidUtility >= this.acceptableUtilityValue) {
                acceptableBids.add(bid);
            }
        }
        // If there is no bid having utility value >= acceptableUtilityValue
        if (acceptableBids.size() == 0) {
            Object[] utilitySortedBidList = this.bidsUtilityMap.keySet().toArray();
            // Getting the bid having highest utility value
            Bid maxUtilityBid = (Bid) utilitySortedBidList[utilitySortedBidList.length - 1];
            acceptableBids.add(maxUtilityBid);
        }
        // Shuffle the bids in order not to select the same bid to offer in each round
        Collections.shuffle(acceptableBids);
        Bid selectedBid = acceptableBids.get(0);

        // First round
        if (this.receivedOffers.size() == 0) {
            offeredBid = selectedBid;
        } else {
            // Hash map of the selected bid
            HashMap<String, Value> createdBid = new HashMap<>();
            // Copying issue value of the selected bid to the hashmap
            for (String issue : issuesList) {
                Value issueValue = selectedBid.getValue(issue);
                createdBid.put(issue, issueValue);
            }

            // From the offered bids (by opponent) history, a bid selected randomly
            int selectedOfferedBidIndex = this.random.nextInt(this.receivedOffers.size());
            Bid selectedOfferedBid = this.receivedOffers.get(selectedOfferedBidIndex);

            // From the issues defined in the domain, an issue selected randomly
            int selectedIssueIndex = this.random.nextInt(issuesList.size());
            String selectedIssue = (String) issuesList.toArray()[selectedIssueIndex];

            // Value of the selected issue in the created bid is replaced with the value of the selected offered bid
            createdBid.put(selectedIssue, selectedOfferedBid.getValue(selectedIssue));
            //The bid is created according to the hash map
            offeredBid = new Bid(createdBid);
        }

        getReporter().log(Level.INFO, "<MyAgent>: I am offering bid: " + offeredBid);
        // Returns an offering action with the bid selected
        return new Offer(partyId, offeredBid);
    }


    private List<Bid> opponentPreferenceProfile(Bid offer) {

        //valueEstimation();

        Bid[] previousWindow = new Bid[kValue];
        Bid[] currentWindow = new Bid[kValue];

        if (this.receivedOffers.size() / kValue > 1 && this.receivedOffers.size() % kValue == 0) {
            for (int i = 0; i < 3; i++) {
                previousWindow[i] = this.receivedOffers.get(((this.receivedOffers.size() / 3) - 2) * 3 + i);
                currentWindow[i] = this.receivedOffers.get(((this.receivedOffers.size() / 3) - 1) * 3 + i);
            }
        }

        Set<String> e = new HashSet<>();
        boolean concession = false;

        Double[] previousWeigths = new Double[this.issueWeights.size()];
        for (int i = 0; i < this.issueWeights.size(); i++) {
            previousWeigths[i] = this.issueWeights.get(this.domain.getIssues().toArray()[i].toString());
        }
        List<Bid> preferenceProfile = new ArrayList<>();
        //System.out.println(this.receivedOffers.get(0).getIssueValues().toString());

        //System.out.println(Arrays.toString(previousWindow[0].getIssues().toArray()));

        /*for(int i = 0; i < previousWindow.length; i++){
            for (int j = 0; j < 2; j++){
                System.out.println(previousWindow[i].getValue(previousWindow[i].getIssues().toArray()[j].toString()));
            }
        }*/

        return preferenceProfile;
    }

    private double Fr(Value value, Bid[] window) {
        int valueCount = 0;
        int deltaValue = 0;
        for (int i = 0; i < kValue; i++) {
            if (window[i].getIssueValues().containsValue(value)) {
                valueCount++;
            }
        }
        //System.out.println(lastReceivedBid.getValue("issue"));
        return 1;
    }

    private void valueEstimation(Bid lastReceivedBid) {

       /*Object [] issueValueList = new Object[3];

        for(int i = 0; i < this.domain.getIssues().size(); i++){
            for(int j = 0; j <this.domain.getValues((String)this.domain.getIssues().toArray()[i]).size().intValue(); j++)
            issueValueList = new Object[]{this.domain.getIssues().toArray()[i].toString(), this.domain.getValues((String) this.domain.getIssues().toArray()[i]).get(j), 0.2};
        }*/

        //ArrayList<Object[]> maxValues = new ArrayList<>();

        for(int i = 0; i < this.receivedOffers.size(); i++){
            System.out.println(i);
            System.out.println(this.receivedOffers.get(i));
        }

        for (int i = 0; i < this.issueValueList.size(); i++) {
            System.out.println("Value " + this.issueValueList.get(i)[1] + " appears " +
                    numeratorCalc((String) this.issueValueList.get(i)[0], (Value) this.issueValueList.get(i)[1]) + " times");
            /*if(numeratorCalc((String) this.issueValueList.get(i)[0], (Value) this.issueValueList.get(i)[1]) >
                    maxValue)
                maxValues.add(new Object[]{this.issueValueList.get(i)[0] ,
                        numeratorCalc((String) this.issueValueList.get(i)[0],
                                (Value) this.issueValueList.get(i)[1])});*/
                /*if(ifValueAppears((String)this.issueValueList.get(i)[0],
                        (Value) this.issueValueList.get(i)[1],
                        this.receivedOffers.get(j)) == 1) {
                    System.out.println(this.receivedOffers.get(j));
                    System.out.println("YES. Value " + this.issueValueList.get(i)[1] + " exist in bid " + j);
                }*/
        }


        /*for(int i = 0; i < this.issueValueList.size(); i++){
            if(ifValueAppears((String)this.issueValueList.get(i)[0],
                    (Value) this.issueValueList.get(i)[1],
                    lastReceivedBid) == 1) {
                System.out.println("YES. Value " + this.issueValueList.get(i)[1] + " exist in bid");

            }
            System.out.println(ifValueAppears((String)listIterator.next()[0],
                    this.domain.getValues((String)listIterator.next()[0]).get(0)
                    (Value) listIterator.next()[1],
                    this.receivedOffers.get(offerCount)));
        }*/


       /* int[] arr= new int[]{1,2,3,4,5};
        System.out.println(Arrays.toString(arr));*/


        /*for(int i = 0; i < this.receivedOffers.size(); i++){
            System.out.println(this.receivedOffers.get(0));
        }*/
    }


    private int ifValueAppears(String issue, Value value, Bid bid) {
        return bid.getValue(issue).equals(value) ? 1 : 0;
    }

    private double numeratorCalc(String issue, Value value){
        int count = 0;
        double exp = 0.4;

        for(int i = 0; i < this.receivedOffers.size(); i++){
            if(ifValueAppears(issue, value, this.receivedOffers.get(i)) == 1)
                count++;
        }

        return Math.pow((count + 1), exp);
    }
}