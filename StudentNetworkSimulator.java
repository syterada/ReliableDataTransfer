import java.util.*;
import java.io.*;

public class StudentNetworkSimulator extends NetworkSimulator
{
    /*
     * Predefined Constants (static member variables):
     *
     *   int MAXDATASIZE : the maximum size of the Message data and
     *                     Packet payload
     *
     *   int A           : a predefined integer that represents entity A
     *   int B           : a predefined integer that represents entity B 
     *
     * Predefined Member Methods:
     *
     *  void stopTimer(int entity): 
     *       Stops the timer running at "entity" [A or B]
     *  void startTimer(int entity, double increment): 
     *       Starts a timer running at "entity" [A or B], which will expire in
     *       "increment" time units, causing the interrupt handler to be
     *       called.  You should only call this with A.
     *  void toLayer3(int callingEntity, Packet p)
     *       Puts the packet "p" into the network from "callingEntity" [A or B]
     *  void toLayer5(String dataSent)
     *       Passes "dataSent" up to layer 5
     *  double getTime()
     *       Returns the current time in the simulator.  Might be useful for
     *       debugging.
     *  int getTraceLevel()
     *       Returns TraceLevel
     *  void printEventList()
     *       Prints the current event list to stdout.  Might be useful for
     *       debugging, but probably not.
     *
     *
     *  Predefined Classes:
     *
     *  Message: Used to encapsulate a message coming from layer 5
     *    Constructor:
     *      Message(String inputData): 
     *          creates a new Message containing "inputData"
     *    Methods:
     *      boolean setData(String inputData):
     *          sets an existing Message's data to "inputData"
     *          returns true on success, false otherwise
     *      String getData():
     *          returns the data contained in the message
     *  Packet: Used to encapsulate a packet
     *    Constructors:
     *      Packet (Packet p):
     *          creates a new Packet that is a copy of "p"
     *      Packet (int seq, int ack, int check, String newPayload)
     *          creates a new Packet with a sequence field of "seq", an
     *          ack field of "ack", a checksum field of "check", and a
     *          payload of "newPayload"
     *      Packet (int seq, int ack, int check)
     *          chreate a new Packet with a sequence field of "seq", an
     *          ack field of "ack", a checksum field of "check", and
     *          an empty payload
     *    Methods:
     *      boolean setSeqnum(int n)
     *          sets the Packet's sequence field to "n"
     *          returns true on success, false otherwise
     *      boolean setAcknum(int n)
     *          sets the Packet's ack field to "n"
     *          returns true on success, false otherwise
     *      boolean setChecksum(int n)
     *          sets the Packet's checksum to "n"
     *          returns true on success, false otherwise
     *      boolean setPayload(String newPayload)
     *          sets the Packet's payload to "newPayload"
     *          returns true on success, false otherwise
     *      int getSeqnum()
     *          returns the contents of the Packet's sequence field
     *      int getAcknum()
     *          returns the contents of the Packet's ack field
     *      int getChecksum()
     *          returns the checksum of the Packet
     *      String getPayload()
     *          returns the Packet's payload
     *
     */

    /*   Please use the following variables in your routines.
     *   int WindowSize  : the window size
     *   double RxmtInterval   : the retransmission timeout
     *   int LimitSeqNo  : when sequence number reaches this value, it wraps around
     */

    public static final int FirstSeqNo = 0;
    private int WindowSize;
    private double RxmtInterval;
    private int LimitSeqNo;
    
    // Add any necessary class variables here.  Remember, you cannot use
    // these variables to send messages error free!  They can only hold
    // state information for A or B.
    // Also add any necessary methods (e.g. checksum of a String)

    //variables to help track state
    public int CurrentSeqNo;//sender, seq # to assign to packet being sent 
    public int lastDelivered;//receiver, seq # of packet last delivered to layer5
    public Packet[] inFlight;//sender, array of packets sent but not yet ack'd
    public int pktsInFlight;//sender, number of packets sent but not yet ack'd
    public int sendBase;//sender, index of beginning of window
    public int next_seq_num; //sender, index of the end of the window
    public Message[] bufferForSender;//buffer if inFlight is full
    public int bufferIndex; //the index of the string in the buffer that's been there longest
    public int bufferNext; //next unoccupied index in the buffer
    public int bufferSize = 10000;
    public double[][] flightTime;//2d array to help track timing stats

    //stats variables
    public double newPacketsSent;
    public double retransmitted;
    public double totalDelivered;
    public double ACKsent;
    public double num_corrupted;
    public double acks_received;
    public double total_RTT;
    public double total_comm;
    public double ack_successfully_received; //ack for an original data packet
    public double ack_noRT; //ack for a data packet that qualifies for RTT calc


    private int calcCheckSum(int seq, int ack, String msg){//helper function to calculate checksum for pkt
        char[] msg_array = new char[msg.length()];
        msg.getChars(0, msg.length(), msg_array, 0);
        int csum = 0;
        for (int i = 0; i < msg_array.length; i++){
            csum += msg_array[i];
        }
        return csum + seq + ack;
    }

    private boolean checkCheckSum(int seq, int ack, int checksum, String msg){
    /*helper function to check for packet corruption. returns true if packet is NOT corrupted*/
        char[] msg_array = new char[msg.length()];
        msg.getChars(0, msg.length(), msg_array, 0);
        int payload_num = 0;
        for (int i = 0; i < msg_array.length; i++){
            payload_num += msg_array[i];
        }
        return (seq + ack + payload_num) == checksum;
    }

    private void managetime(double end, int index){//helper function to update time stats figures
        double elapsed = end - flightTime[index][0];
        if(flightTime[index][1] == 0.0){
            total_RTT += elapsed;
            ack_noRT++;
        }
        total_comm += elapsed;
        ack_successfully_received++;
        flightTime[index][1] = 0.0;
    }

    // This is the constructor.  Don't touch!
    public StudentNetworkSimulator(int numMessages,
                                   double loss,
                                   double corrupt,
                                   double avgDelay,
                                   int trace,
                                   int seed,
                                   int winsize,
                                   double delay)
    {
        super(numMessages, loss, corrupt, avgDelay, trace, seed);
	WindowSize = winsize;
	LimitSeqNo = winsize*2; // set appropriately
	RxmtInterval = delay;
    }

    
    // This routine will be called whenever the upper layer at the sender [A]
    // has a message to send.  It is the job of your protocol to insure that
    // the data in such a message is delivered in-order, and correctly, to
    // the receiving upper layer.
    protected void aOutput(Message message){

        if(pktsInFlight == WindowSize){//if inFlight is full, buffer the message
            Message to_buffer = message;
            bufferForSender[bufferNext] = to_buffer;
            bufferNext ++;
            if(bufferIndex == -1){
                bufferIndex ++;
            }
            System.out.println("Buffering packet..."); //print statement for debugging
            
        }else{
            if(pktsInFlight < 1){//edge case for first packet being sent or if there are no packets currently in flight

                int seq = CurrentSeqNo;
                CurrentSeqNo ++;
                if (CurrentSeqNo == LimitSeqNo){ //need to wrap around
                    CurrentSeqNo = 0; 
                }
                pktsInFlight = 1;
                newPacketsSent++;
                int ack = 0;
                int csum = calcCheckSum(seq, ack, message.getData());        
                Packet p = new Packet(seq, ack, csum, message.getData());
                inFlight[seq % WindowSize] = p;
                double start_time = getTime();
                flightTime[seq % WindowSize][0] = start_time;
                System.out.println("inFlight index:" + (seq % WindowSize)); //print statement for debugging
                toLayer3(0, p);
                startTimer(0,RxmtInterval);
                System.out.println("packet " + seq + " sent!"); //print statement for debugging
                System.out.println("pktsInFlight: " + pktsInFlight); //print statement for debugging
            }else{
                newPacketsSent++;
                pktsInFlight++;
                int seq = CurrentSeqNo;
                System.out.println(seq); //print statement for debugging
                CurrentSeqNo ++;
                if (CurrentSeqNo == LimitSeqNo){ //need to wrap around
                    CurrentSeqNo = 0;
                }
                int ack = 0;
                int csum = calcCheckSum(seq, ack, message.getData());        
                Packet p = new Packet(seq, ack, csum, message.getData());
                inFlight[seq % WindowSize] = p;
                double start_time = getTime();
                flightTime[seq % WindowSize][0] = start_time;
                System.out.println("inFlight index:" + (seq % WindowSize)); //print statement for debugging
                toLayer3(0, p);
                if(pktsInFlight == 0){
                    startTimer(0,RxmtInterval);
                }
                System.out.println("packet " + seq + " sent!"); //print statement for debugging
                System.out.println("pktsInFlight: " + pktsInFlight); //print statement for debugging
            }
        }
    }
    
    // This routine will be called whenever a packet sent from the B-side 
    // (i.e. as a result of a toLayer3() being done by a B-side procedure)
    // arrives at the A-side.  "packet" is the (possibly corrupted) packet
    // sent from the B-side.
    protected void aInput(Packet packet){

        int seq = packet.getSeqnum();
        int ack = packet.getAcknum();
        int csum = packet.getChecksum();
        boolean not_corrupted = checkCheckSum(seq, ack, csum, packet.getPayload());
        acks_received++;
        System.out.println("Seq: " + seq + " sendBase: " + sendBase); //print statement for debugging
        if(not_corrupted){
            if(seq == sendBase){//if oldest packet currently in flight
                if(sendBase == LimitSeqNo-1){//wrap around
                    sendBase = 0;
                    next_seq_num++;
                }else{
                    sendBase++;
                    next_seq_num++;
                }
                if(next_seq_num == LimitSeqNo){//wrap window around
                    next_seq_num = 0;
                }
                pktsInFlight --;
                inFlight[seq % WindowSize] = null;
                double end = getTime();
                managetime(end, (seq % WindowSize));
                //restart timer
                stopTimer(0);
                if(pktsInFlight > 0){
                    startTimer(0, RxmtInterval);
                }
                System.out.println("ack received! new sendBase: " + sendBase + " new next_seq_num: " + next_seq_num); //print statement for debugging
                if(bufferIndex >= 0 && bufferForSender[bufferIndex] != null){//if there's something in the buffer, send that message
                    Message next_message = bufferForSender[bufferIndex];
                    bufferIndex++;
                    aOutput(next_message);
                    System.out.println("Sending packet from buffer index " + (bufferIndex - 1)); //print statement for debugging
                }

            }else if((sendBase < seq && seq <= next_seq_num) || ((seq < ((sendBase + WindowSize) % WindowSize)) && (next_seq_num < sendBase))||
            (sendBase < seq && seq < (next_seq_num + LimitSeqNo)) &&  (next_seq_num < sendBase)){
            
                //out of order ack
                //first part of statement: no wrap around, window is of consecutive numbers
                //second part of statement: sendBase > seq but seq still in window. 
                //third part of statement: window wraps around, but seq hasn't wrapped yet.
                double end = getTime();
                for (int i = (sendBase % WindowSize); i < inFlight.length; i++){//clear out inFlight
                    if(inFlight[i] != null){
                        int seq_i = inFlight[i].getSeqnum();
                        if(sendBase <= seq_i && seq_i <= seq){//no wrap
                            inFlight[i] = null;
                            managetime(end, i);
                            pktsInFlight--;
                            if(i == (inFlight.length - 1)){
                                i = -1;
                            }
                            if(seq_i == seq){//we've caught up to where we want to be
                                break;
                            }
                        }else if(next_seq_num < sendBase){//wrap
                            if((sendBase <= seq_i && seq_i < LimitSeqNo) ||
                              (seq_i <= seq && seq <= next_seq_num)){
                                inFlight[i] = null;
                                managetime(end, i);
                                pktsInFlight--;
                                if(i == (inFlight.length - 1)){
                                i = -1;
                            }
                                if(seq_i == seq){
                                    break;
                                }
                            }
                        }
                    }
                }
                sendBase = seq + 1; //make new sendBase and next_seq_nums
                if (sendBase == LimitSeqNo){
                    sendBase = 0;
                }
                next_seq_num = sendBase + (WindowSize - 1);
                if(next_seq_num >= LimitSeqNo){
                    next_seq_num = next_seq_num % WindowSize;
                }
                //restart timer as above
                stopTimer(0);
                if(pktsInFlight > 0){
                    startTimer(0, RxmtInterval);
                }
                System.out.println("ack received! new sendBase: " + sendBase + " new next_seq_num: " + next_seq_num); //print statement for debugging
                if(bufferIndex >= 0 && bufferForSender[bufferIndex] != null){//same as above, if there's something in the buffer, send it
                    Message next_message = bufferForSender[bufferIndex];
                    bufferIndex++;
                    aOutput(next_message);
                    System.out.println("Sending packet from buffer index " + (bufferIndex - 1)); //print statement for debugging
                }
                
            }else{//else duplicate ack, ignore
                System.out.println("duplicate ack, ignored"); //print statement for debugging
            }
        }else{
            System.out.println("Ack corrupted, ignored"); //print statement for debugging
            num_corrupted++;
        }
    }
    
    // This routine will be called when A's timer expires (thus generating a 
    // timer interrupt). You'll probably want to use this routine to control 
    // the retransmission of packets. See startTimer() and stopTimer(), above,
    // for how the timer is started and stopped. 
    protected void aTimerInterrupt(){
    /*start timer for most recently sent packet (suppose packets 5, 6, 7, 8 are in flight)
    sender then receives ack5, reset timer, now timer tracks ack6.*/
    if(pktsInFlight > 0){
        System.out.println("Timer timed out! Going back N!"); //print statement for debugging
        System.out.println("pktsInFlight: " + pktsInFlight); //print statement for debugging
        System.out.println("sendBase: "+ sendBase); //print statement for debugging
        int num_pkts_to_send = pktsInFlight;
        pktsInFlight = 0;
        for (int i = sendBase; i < LimitSeqNo; i++){
            int IFindex = (i % WindowSize);
            if(inFlight[IFindex] != null && num_pkts_to_send > 0){//if we have a packet to send
                Packet p = inFlight[IFindex];
                if(i == sendBase){//if this is the sendBase we have to restart the timer, don't have to do that for other pkts
                    toLayer3(0,p);
                    startTimer(0, RxmtInterval);
                    pktsInFlight ++; 
                    retransmitted++;
                    num_pkts_to_send--;
                    flightTime[IFindex][1] = 1.0;
                    System.out.println("restarting timer..."); //print statement for debugging
                    System.out.println("resending " + p.getSeqnum() + " due to GBN"); //print statement for debugging
                }else{//every other packet
                    toLayer3(0,p);
                    pktsInFlight ++;
                    retransmitted++;
                    num_pkts_to_send--;
                    flightTime[IFindex][1] = 1.0;
                    System.out.println("resending " + p.getSeqnum() + " due to GBN"); //print statement for debugging
                }
            }
        }
        for(int i = 0; i < sendBase % WindowSize; i++){//for wrap around cases
            if(inFlight[i] != null && num_pkts_to_send > 0){
                Packet p = inFlight[i];
                toLayer3(0,p);
                pktsInFlight ++; 
                retransmitted++;
                num_pkts_to_send--;
                flightTime[i][1] = 1.0;
                System.out.println("resending " + p.getSeqnum()  + " due to GBN"); //print statement for debugging
            }else{
                break;
            }
        }
    }
    }
    
    // This routine will be called once, before any of your other A-side 
    // routines are called. It can be used to do any required
    // initialization (e.g. of member variables you add to control the state
    // of entity A).
    protected void aInit(){

        inFlight = new Packet[WindowSize];
        bufferForSender = new Message[bufferSize];
        bufferIndex = -1; //garbage to show nothing is in the buffer yet, will be variable to show which index we should pull from buffer next
        bufferNext = 0; //show that the next index to put sthg in the buffer is this value
        sendBase = 0;
        next_seq_num = WindowSize - 1;
        pktsInFlight = -1;
        newPacketsSent = 0;
        retransmitted = 0;
        num_corrupted = 0;
        CurrentSeqNo = FirstSeqNo;
        total_RTT = 0;
        total_comm = 0;
        ack_successfully_received = 0;
        ack_noRT = 0;
        flightTime = new double[WindowSize][2];//column 0 is start time, column 1 is a flag for whether pkt has been resent, 0 for no, 1 for yes
    }
    
    // This routine will be called whenever a packet sent from the B-side 
    // (i.e. as a result of a toLayer3() being done by an A-side procedure)
    // arrives at the B-side.  "packet" is the (possibly corrupted) packet
    // sent from the A-side.
    protected void bInput(Packet packet){

        int seq = packet.getSeqnum();
        System.out.println("packet " + seq + " arrived! Expecting packet: " + (lastDelivered + 1)); //print statement for debugging
        Packet p; 

        if(checkCheckSum(seq, packet.getAcknum(), packet.getChecksum(), packet.getPayload())){
            /*if a packet is received uncorrupted*/
            if((seq - 1 == lastDelivered) || ((seq == 0) && (lastDelivered == LimitSeqNo - 1))){
                /*packet is new and in order, send ack*/
                toLayer5(packet.getPayload());
                lastDelivered = seq;
                p = new Packet(seq, 1, seq+1);
                toLayer3(1,p);
                totalDelivered++;
                System.out.println("packet " + seq + " delivered successfully! Sending ack"); //print statement for debugging

            }else{//re-ack bc out of order
                p = new Packet(lastDelivered, 1, lastDelivered+1);
                toLayer3(1, p);
                System.out.println("packet " + seq + " delivered o-o-o! Sending re-ack"); //print statement for debugging
            }
        }else{/*packet corrupted, send a re-ack */
            p = new Packet(lastDelivered, 1, lastDelivered+1);
            toLayer3(1, p);
            num_corrupted++;
            System.out.println("packet delivered corrupted! Sending re-ack"); //print statement for debugging
        }
        ACKsent++;//we send an ack in every situation, just have one statement for all cases
    }
    
    // This routine will be called once, before any of your other B-side 
    // routines are called. It can be used to do any required
    // initialization (e.g. of member variables you add to control the state
    // of entity B).
    protected void bInit(){

        lastDelivered = -1;//garbage to show we haven't received any pkts yet
        ACKsent = 0;

    }

    // Use to print final statistics
    protected void Simulation_done()
    {
        double total_packets = newPacketsSent + retransmitted;
        double ratio_lost = ((retransmitted + newPacketsSent - acks_received)/total_packets);
        double ratio_corrupted = (num_corrupted/(total_packets+ACKsent));
    	// TO PRINT THE STATISTICS, FILL IN THE DETAILS BY PUTTING VARIABLE NAMES. DO NOT CHANGE THE FORMAT OF PRINTED OUTPUT
    	System.out.println("\n\n===============STATISTICS=======================");
    	System.out.println("Number of original packets transmitted by A:" + newPacketsSent);
    	System.out.println("Number of retransmissions by A:" + retransmitted);
    	System.out.println("Number of data packets delivered to layer 5 at B:" + totalDelivered);
    	System.out.println("Number of ACK packets sent by B:" + ACKsent);
    	System.out.println("Number of corrupted packets:" + num_corrupted);
    	System.out.println("Ratio of lost packets:" + ratio_lost);
    	System.out.println("Ratio of corrupted packets:" + ratio_corrupted);
    	System.out.println("Average RTT:" + total_RTT/ack_noRT); 
    	System.out.println("Average communication time:" + total_comm/(ack_successfully_received)); 
    	System.out.println("==================================================");

    	// PRINT YOUR OWN STATISTIC HERE TO CHECK THE CORRECTNESS OF YOUR PROGRAM
    	System.out.println("\nEXTRA:");
        System.out.println("Total packets sent:" + total_packets);
        System.out.println("Acks received by A:" + acks_received);
        System.out.println("Total RTT:" + total_RTT);
        System.out.println("Total Communication Time:" + total_comm);
        System.out.println("Original pkts ack'd: " + ack_successfully_received);
        System.out.println("Packets not resent: " + ack_noRT);
    	// EXAMPLE GIVEN BELOW
    	//System.out.println("Example statistic you want to check e.g. number of ACK packets received by A :" + "<YourVariableHere>"); 
    }	

}