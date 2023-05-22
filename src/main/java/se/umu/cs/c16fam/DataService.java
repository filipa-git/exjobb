package se.umu.cs.c16fam;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.ArrayList;

/**
 * @author: filip
 * @since: 2023-05-19.
 */
public interface DataService extends Remote {
    int uploadData(int id, ArrayList<Integer> data, boolean done) throws
            RemoteException;
}
