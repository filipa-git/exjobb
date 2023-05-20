package se.umu.cs.c16fam;

import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author: filip
 * @since: 2023-05-19.
 */
public class DataServiceImpl implements DataService {
    private final int CACHE_LIMIT = 8;

    private BlockingQueue<ArrayList<Integer>> outQueue;
    private ArrayList<Integer> outBuf = new ArrayList<>(CACHE_LIMIT);
    private ReentrantLock countLock = new ReentrantLock();
    private int expectedSorters = 0;
    private ReentrantLock sortLock = new ReentrantLock();
    private int bufCount = 0;
    private ConcurrentHashMap<Integer, ArrayList<Integer>> buffers = new
            ConcurrentHashMap<>();
    private ConcurrentHashMap<Integer, ReentrantLock> bufLocks = new
            ConcurrentHashMap<>();
    private Set<Integer> doneSet = ConcurrentHashMap.newKeySet();

    public DataServiceImpl(BlockingQueue<ArrayList<Integer>> out, int
     nSorters) {
        outQueue = out;
        expectedSorters = nSorters;
        sortLock.lock();
    }

    @Override
    public int uploadData(int id, ArrayList<Integer> data, boolean done) {
        int n = id;
        //Add data for first time
        if (id < 0) {
            //Increase buffer count
            countLock.lock();
            try {
                n = bufCount;
                bufCount++;
            }
            finally {
                countLock.unlock();
            }

            //Add new buffer and lock
            buffers.put(n, data);
            bufLocks.put(n, new ReentrantLock());

            //Unlock when all expected buffers are present
            if (bufCount == expectedSorters)
                sortLock.unlock();
        }
        else {
            //Aquire lock
            ReentrantLock lock = bufLocks.get(n);
            if (lock == null) {
                System.err.println("Could not find lock " + n);
                return -1;
            }
            lock.lock();

            //Add data
            try {
                buffers.put(n, data);
            }
            finally {
                lock.unlock();
            }
        }

        //Check if done
        if (done)
            doneSet.add(n);

        return n;
    }

    public void sortData() throws RemoteException {
        //Wait for all buffers to be present
        sortLock.lock();
        System.err.println("Beginning final sorting");

        //Aquire all buffer locks
        int nBuf;
        countLock.lock();
        try {
            nBuf = bufCount;
        }
        finally {
            countLock.unlock();
        }

        for (int i = 0; i < nBuf; i++) {
            try {
                bufLocks.get(i).lock();
            }
            catch (Exception e) {
                e.printStackTrace();
                return;
            }
        }

        try {
            //K-way merge sort
            int min;
            int bufId;
            int outCount = 0;
            boolean done = false;
            boolean repeat = false;//failsafe if sorter crashes

            while (!done) {
                min = -1;
                bufId = -1;

                //Compare first elements in all buffers
                for (int i = 0; i < nBuf; i++) {
                    ArrayList<Integer> b = buffers.get(i);
                    if (b != null) {
                        if (b.isEmpty()) {
                            //remove if done or repeated
                            if (doneSet.contains(i) || repeat) {
                                buffers.remove(i);
                                //check if all is done
                                if (buffers.isEmpty())
                                    done = true;
                                repeat = false;
                            } else {
                                //allow more data
                                bufLocks.get(i).unlock();
                                bufLocks.get(i).lock();
                                i--; //repeat this step in for-loop
                                repeat = true;
                            }
                        } else if (b.get(0) < min || min == -1) {
                            min = b.get(0);
                            bufId = i;
                            repeat = false;
                        }
                    }
                }

                if (bufId > -1) {
                    //Move min from original buffer to output buffer
                    outBuf.add(buffers.get(bufId).remove(0));
                    outCount++;
                }
                //Send data if cache limit reached or done
                if (outCount == CACHE_LIMIT || done) {
                    outQueue.add(outBuf);
                    outBuf = new ArrayList<>(CACHE_LIMIT);
                    outCount = 0;
                }
            }
        }
        finally {
            //Release all locks
            sortLock.unlock();
            for (int i = 0; i < nBuf; i++) {
                try {
                    bufLocks.get(i).unlock();
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
