package com.android.leanlauncher;

import android.util.SparseArray;

import java.io.Serializable;

/**
 * Serializable sparse array for bundles
 */
public class SerializableSparseArray<E> extends SparseArray<E> implements Serializable, Cloneable {

    private static final long serialVersionUID = 16569L;

    @Override
    public SerializableSparseArray<E> clone() {
        return (SerializableSparseArray<E>) super.clone();
    }
}
