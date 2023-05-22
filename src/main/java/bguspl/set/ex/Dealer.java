package bguspl.set.ex;

import bguspl.set.Env;

import java.util.List;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Vector;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;
    public Vector<Player> setCalls = new Vector<Player>();
    private long globalTimer=0;
    private long displayTime=60000;
    protected Thread dealerThread;
    public volatile boolean isFree=true;//dealer is free to check sets

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate=false;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    private int sleep=1000;//amount of time for dealer to sleep between timer update

    
    private boolean firstLoop = true;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        terminate=false;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");
        dealerThread=Thread.currentThread();
        while (!shouldFinish()) {
            placeCardsOnTable();
            for (int i = 0; i < players.length; i++) {
                players[i].setAvailability(true);
            }
            if(firstLoop){//starting players
                for (int i = 0; i < players.length; i++) {
                    players[i].setThread();
                    players[i].getThread().start();
                }
            }
            displayTime=env.config.turnTimeoutMillis;
            env.ui.setCountdown(displayTime, false);
            timerLoop();
            for (int i = 0; i < players.length; i++) {//ignoring all players' presses until there are new cards on table
                players[i].setAvailability(false);
            }
            updateTimerDisplay(true);
            removeAllCardsFromTable();
        }
        announceWinners();
        terminate();
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
    }

      /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        reshuffleTime=System.currentTimeMillis() + env.config.turnTimeoutMillis+500;
        globalTimer = System.currentTimeMillis();
        if(!firstLoop){
            for (int i = 0; i < players.length; i++) {
                if(!players[i].isHuman()){
                    players[i].getAiThread().interrupt();
                    players[i].getThread().interrupt();
                }
            }
        }
        firstLoop=false;
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
        }
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() { 
        for (int i = players.length-1; i >=0 ; i--) {
            players[i].terminate();
            players[i].getThread().interrupt();
            players[i].getAiThread().interrupt();
            try { players[i].getThread().join(); } catch (InterruptedException ignored) {}
        }
        terminate=true;
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        // TODO implement
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        synchronized(table){
            for (int i = 0; i < players.length; i++) {
                players[i].removeAllTokens();
            }
            List<Integer> randSlots = new LinkedList<>();//for random cards placement 
            for (int i = 0; i < 12; i++) {
                randSlots.add(i);
            }
            Collections.shuffle(randSlots);
            Collections.shuffle(deck);
            boolean over=deck.isEmpty();
            for (int i = 0; i < 12&& !over; i++) {
                table.placeCard(deck.remove(0), randSlots.get(i));
                over=deck.isEmpty();
            }
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
      long currTime = System.currentTimeMillis();
      if(System.currentTimeMillis()-globalTimer < sleep && System.currentTimeMillis()-globalTimer>0){//woke up because of a set
            if(!setCalls.isEmpty()){
                isFree=false;
                Player p=setCalls.remove(0);
                synchronized(p){
                setTest(p);
                }
                isFree=true;
            }
            
            try{Thread.currentThread().sleep(sleep-(currTime - globalTimer));
            }
                catch(InterruptedException ignored){}
        }

    else{
        if(!setCalls.isEmpty()){
            isFree=false;
            Player p=setCalls.remove(0);
            synchronized(p){
            setTest(p);
            }
            isFree=true;
        }
        try{Thread.currentThread().sleep(sleep);
        }
            catch(InterruptedException ignored){}
        }
        globalTimer=currTime;
    }

    public void addSet(Player player){
        synchronized(setCalls){
            setCalls.add(player);   
        }
    }

    //checks if the player's set is legal
    private void setTest(Player player){
        if(player.tokensCounter==3){
            int[] cards = player.tokensOnTable[1];
            boolean overlappingSet = false;
            int cardsOverlapped=0;

            //checking if the currest set request overlapped with previous set
            for (int i = 0; i < cards.length; i++) {
                int arrCard = player.tokensOnTable[1][i];
                Integer tableCard = table.slotToCard[player.tokensOnTable[0][i]];
                if(tableCard==null){//can happen when it overlapped with a set and there are less then 12 cards left
                    overlappingSet = true;
                    player.tokensOnTable[1][i]=-1;
                    player.tokensOnTable[0][i]=-1;
                    cardsOverlapped++;
                }
                else if(arrCard!=tableCard){//the current card is no longer on the table
                    overlappingSet = true;
                    player.tokensOnTable[1][i]=-1;
                    player.tokensOnTable[0][i]=-1;
                    cardsOverlapped++;
                }
            }
            if(overlappingSet){//the set is not relevant, the player should continue
                player.tokensCounter=player.tokensCounter-cardsOverlapped;
                player.getThread().interrupt();
            }
            else{
                boolean legal= env.util.testSet(cards);
                if(!legal){
                    player.setAvailability(false);
                    player.shouldSleep=2;
                    player.getThread().interrupt();
                }
                else{
                    player.setAvailability(false);
                    player.shouldSleep=1;
                    replaceSetCards(player.tokensOnTable[0]);
                    player.getThread().interrupt();
                }
            }
            isFree=true;
        }
        else
            player.getThread().interrupt();
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if(reset){
            sleep=1000;
            env.ui.setCountdown(env.config.turnTimeoutMillis+500, false);
            reshuffleTime=System.currentTimeMillis() + env.config.turnTimeoutMillis;
            isFree=true;
        }
        else{
            env.ui.setCountdown(reshuffleTime-System.currentTimeMillis(), false);
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        synchronized(table){
            for (int i = 0; i < players.length; i++) {
                players[i].removeAllTokens();
            }
            env.ui.removeTokens();
            List<Integer> randSlots = new LinkedList<>();//for random cards removal
            for (int i = 0; i < 12; i++) {
                randSlots.add(i);
            }
            Collections.shuffle(randSlots);
            for (int i = 0; i < 12; i++) {
                int j = randSlots.get(i);
                if(table.slotToCard[j]!=null){
                    deck.add(table.slotToCard[j]);
                    table.removeCard(j);
                }
            }
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int highest=players[0].score();
        int numOfWinners=1;
        for (int i = 1; i < players.length; i++) {//finding the highest score and the number players with that score
            if(highest<players[i].score()){
                highest=players[i].score();
                numOfWinners=1;
            }  
            else if(highest==players[i].score()){
                numOfWinners++;
            }
        }
        int[] winners=new int[numOfWinners];
        int index=0;
        for (int i = 0; i < players.length; i++) {//finding the winners
            if(players[i].score()==highest){
                winners[index]=players[i].getId();
                index++;
            }
        }
        env.ui.announceWinner(winners);
    }

    //when a set is legal replace the set cards if exists.
    public void replaceSetCards(int[]setIndex){
        for (int i = 0; i < setIndex.length; i++) {//removing set cards from table and deck
            env.ui.removeTokens(setIndex[i]);
            deck.remove(table.slotToCard[setIndex[i]]);
            table.removeCard(setIndex[i]);
            table.slotToCard[setIndex[i]]=null;
        }
        boolean emptyDeck= deck.isEmpty();
        for (int i = 0; i < setIndex.length&& !emptyDeck; i++) {//putting new cards on the set's indexes 
            table.placeCard(deck.remove(0), setIndex[i]);
            emptyDeck=deck.isEmpty(); 
        }
        //resetting the timer after a set is found
        globalTimer=System.currentTimeMillis();//timer start
        reshuffleTime=globalTimer+env.config.turnTimeoutMillis;
        displayTime=env.config.turnTimeoutMillis;
        env.ui.setCountdown(displayTime, false);
    }
    public boolean getTerminate(){return terminate;}

    public void setTerminate(){
        terminate=!terminate;
    }
}