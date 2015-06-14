/*
 *
 *  * Copyright 2015 Skymind,Inc.
 *  *
 *  *    Licensed under the Apache License, Version 2.0 (the "License");
 *  *    you may not use this file except in compliance with the License.
 *  *    You may obtain a copy of the License at
 *  *
 *  *        http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *    Unless required by applicable law or agreed to in writing, software
 *  *    distributed under the License is distributed on an "AS IS" BASIS,
 *  *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *    See the License for the specific language governing permissions and
 *  *    limitations under the License.
 *
 *
 */

package org.nd4j.linalg.api.ndarray;


import com.google.common.primitives.Ints;
import org.nd4j.linalg.api.blas.BlasBufferUtil;
import org.nd4j.linalg.api.buffer.DataBuffer;
import org.nd4j.linalg.api.buffer.DoubleBuffer;
import org.nd4j.linalg.api.buffer.FloatBuffer;
import org.nd4j.linalg.api.complex.IComplexNDArray;
import org.nd4j.linalg.api.complex.IComplexNumber;
import org.nd4j.linalg.api.instrumentation.Instrumentation;
import org.nd4j.linalg.api.ops.impl.accum.Max;
import org.nd4j.linalg.api.ops.impl.accum.*;
import org.nd4j.linalg.api.ops.impl.accum.Min;
import org.nd4j.linalg.api.ops.impl.scalar.*;
import org.nd4j.linalg.api.ops.impl.scalar.comparison.ScalarEquals;
import org.nd4j.linalg.api.ops.impl.scalar.comparison.ScalarGreaterThan;
import org.nd4j.linalg.api.ops.impl.scalar.comparison.ScalarLessThan;
import org.nd4j.linalg.api.ops.impl.scalar.comparison.ScalarNotEquals;
import org.nd4j.linalg.api.ops.impl.transforms.Negative;
import org.nd4j.linalg.api.ops.impl.transforms.arithmetic.AddOp;
import org.nd4j.linalg.api.ops.impl.transforms.arithmetic.DivOp;
import org.nd4j.linalg.api.ops.impl.transforms.arithmetic.MulOp;
import org.nd4j.linalg.api.ops.impl.transforms.arithmetic.SubOp;
import org.nd4j.linalg.api.ops.impl.transforms.comparison.*;
import org.nd4j.linalg.factory.NDArrayFactory;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.Indices;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.nd4j.linalg.indexing.conditions.Condition;
import org.nd4j.linalg.util.ArrayUtil;
import org.nd4j.linalg.util.LinAlgExceptions;
import org.nd4j.linalg.util.Shape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.*;

import static org.nd4j.linalg.util.ArrayUtil.calcStrides;
import static org.nd4j.linalg.util.ArrayUtil.calcStridesFortran;



/**
 * NDArray: (think numpy)
 * <p/>
 * A few things of note.
 * <p/>
 * An NDArray can have any number of dimensions.
 * <p/>
 * An NDArray is accessed via strides.
 * <p/>
 * Strides are how to index over
 * a contiguous block of data.
 * <p/>
 * This block of data has 2 orders(as of right now):
 * fortran and c
 *
 * @author Adam Gibson
 */
public abstract class BaseNDArray implements INDArray {


    protected static final Logger log = LoggerFactory.getLogger(BaseNDArray.class);
    /**
     *
     */
    private static final long serialVersionUID = 3285982317165542614L;
    protected int[] shape;
    protected int[] stride;
    protected int offset = 0;
    protected char ordering;
    protected DataBuffer data;
    protected int rows, columns;
    protected int length;
    protected INDArray linearView;
    protected boolean cleanedUp = false;
    protected transient WeakReference<INDArray> ref;
    protected int firstNonOneStride = -1;
    protected int majorStride = -1;
    protected Boolean isVector = null;
    protected Boolean isScalar = null;

    public BaseNDArray() {
    }


    public BaseNDArray(DataBuffer buffer) {
        this.data = buffer;
        init(new int[]{1, (int) buffer.length()});
    }

    public BaseNDArray(DataBuffer buffer, int[] shape, int[] stride, int offset, char ordering) {
        this.data = buffer;
        if (ArrayUtil.prod(shape) > buffer.length())
            throw new IllegalArgumentException("Shape must be <= buffer length");
        this.stride = stride;
        this.offset = offset;
        this.ordering = ordering;
        init(shape);

    }

    public BaseNDArray(double[][] data) {
        this(Nd4j.createBuffer(ArrayUtil.flatten(data)), new int[]{data.length, data[0].length});

        for (int r = 0; r < rows; r++) {
            assert (data[r].length == columns);
        }

        this.data = Nd4j.createBuffer(length);


        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                putScalar(new int[]{r, c}, data[r][c]);
            }
        }
    }


    /**
     * Create with the specified shape and buffer
     *
     * @param shape  the shape
     * @param buffer the buffer
     */
    public BaseNDArray(int[] shape, DataBuffer buffer) {
        this.data = buffer;
        init(shape);
    }

    /**
     * Create this ndarray with the given data and shape and 0 offset
     *
     * @param data  the data to use
     * @param shape the shape of the ndarray
     */
    public BaseNDArray(float[] data, int[] shape, char ordering) {
        this(data, shape, 0, ordering);
    }

    /**
     * @param data     the data to use
     * @param shape    the shape of the ndarray
     * @param offset   the desired offset
     * @param ordering the ordering of the ndarray
     */
    public BaseNDArray(float[] data, int[] shape, int offset, char ordering) {
        this(data, shape, ordering == NDArrayFactory.C ? calcStrides(shape) : calcStridesFortran(shape), offset);

    }


    /**
     * Construct an ndarray of the specified shape
     * with an empty data array
     *
     * @param shape    the shape of the ndarray
     * @param stride   the stride of the ndarray
     * @param offset   the desired offset
     * @param ordering the ordering of the ndarray
     */
    public BaseNDArray(int[] shape, int[] stride, int offset, char ordering) {
        this(Nd4j.createBuffer(ArrayUtil.prod(shape)), shape, stride, offset, ordering);
    }


    /**
     * Create the ndarray with
     * the specified shape and stride and an offset of 0
     *
     * @param shape    the shape of the ndarray
     * @param stride   the stride of the ndarray
     * @param ordering the ordering of the ndarray
     */
    public BaseNDArray(int[] shape, int[] stride, char ordering) {
        this(shape, stride, 0, ordering);
    }

    public BaseNDArray(int[] shape, int offset, char ordering) {
        this(shape, Nd4j.getStrides(shape, ordering), offset, ordering);
    }


    public BaseNDArray(int[] shape) {
        this(shape, 0, Nd4j.order());
    }


    /**
     * Creates a new <i>n</i> times <i>m</i> <tt>DoubleMatrix</tt>.
     *
     * @param newRows    the number of rows (<i>n</i>) of the new matrix.
     * @param newColumns the number of columns (<i>m</i>) of the new matrix.
     */
    public BaseNDArray(int newRows, int newColumns, char ordering) {
        this.ordering = ordering;
        this.data = Nd4j.createBuffer(newRows * newColumns);
        init(new int[]{newRows, newColumns});
    }


    /**
     * Create an ndarray from the specified slices.
     * This will go through and merge all of the
     * data from each slice in to one ndarray
     * which will then take the specified shape
     *
     * @param slices the slices to merge
     * @param shape  the shape of the ndarray
     */
    public BaseNDArray(List<INDArray> slices, int[] shape, char ordering) {
        this(slices, shape, Nd4j.getStrides(shape), ordering);
    }


    /**
     * Create an ndarray from the specified slices.
     * This will go through and merge all of the
     * data from each slice in to one ndarray
     * which will then take the specified shape
     *
     * @param slices the slices to merge
     * @param shape  the shape of the ndarray
     */
    public BaseNDArray(List<INDArray> slices, int[] shape, int[] stride, char ordering) {
        int[] thisShape = Ints.concat(new int[]{slices.size()},slices.get(0).shape());
        DataBuffer ret = slices.get(0).data().dataType() == (DataBuffer.Type.FLOAT) ?
                Nd4j.createBuffer(new float[ArrayUtil.prod(thisShape)]) :
                Nd4j.createBuffer(new double[ArrayUtil.prod(thisShape)]);

        this.stride = stride;
        this.ordering = ordering;
        this.data = ret;

        init(thisShape);

        for (int i = 0; i < slices(); i++) {
            putSlice(i, slices.get(i));
        }
    }


    public BaseNDArray(float[] data, int[] shape, int[] stride, char ordering) {
        this(data, shape, stride, 0, ordering);
    }


    public BaseNDArray(float[] data, int[] shape, int[] stride, int offset, char ordering) {


        this.offset = offset;
        this.stride = ArrayUtil.copy(stride);
        this.ordering = ordering;

        if (data != null && data.length > 0) {
            this.data = Nd4j.createBuffer(data);
            if (offset >= data.length)
                throw new IllegalArgumentException("invalid offset: must be < data.length");


        }

        init(shape);


    }

    public BaseNDArray(DataBuffer data, int[] shape, int[] stride, int offset) {
        this.data = data;
        this.stride = stride;
        this.offset = offset;
        this.ordering = Nd4j.order();
        init(shape);

    }


    public BaseNDArray(int[] data, int[] shape, int[] strides) {
        this(Nd4j.createBuffer(data), shape, strides);
    }

    public BaseNDArray(DataBuffer data, int[] shape) {
        this(data, shape, Nd4j.getStrides(shape, Nd4j.order()), 0, Nd4j.order());
    }

    public BaseNDArray(DataBuffer buffer, int[] shape, int offset) {
        this(buffer, shape, Nd4j.getStrides(shape), offset);
    }

    public BaseNDArray(double[] data, int[] shape, char ordering) {
        this(Nd4j.createBuffer(data), shape, ordering);
    }

    public BaseNDArray(double[] data, int[] shape, int[] stride, int offset, char ordering) {
        this(Nd4j.createBuffer(data), shape, stride, offset, ordering);
    }

    public BaseNDArray(float[] data, char order) {
        this(Nd4j.createBuffer(data), order);
    }

    public BaseNDArray(DataBuffer floatBuffer, char order) {
        this(floatBuffer, new int[]{(int) floatBuffer.length()}, Nd4j.getStrides(new int[]{(int) floatBuffer.length()}), 0, order);
    }

    public BaseNDArray(DataBuffer buffer, int[] shape, int[] strides) {
        this(buffer, shape, strides, 0, Nd4j.order());
    }


    /**
     * Create this ndarray with the given data and shape and 0 offset
     *
     * @param data  the data to use
     * @param shape the shape of the ndarray
     */
    public BaseNDArray(float[] data, int[] shape) {
        this(data, shape, 0);
    }

    public BaseNDArray(float[] data, int[] shape, int offset) {
        this(data, shape, offset, Nd4j.order());

    }

    /**
     * Construct an ndarray of the specified shape
     * with an empty data array
     *
     * @param shape  the shape of the ndarray
     * @param stride the stride of the ndarray
     * @param offset the desired offset
     */
    public BaseNDArray(int[] shape, int[] stride, int offset) {
        this(new float[ArrayUtil.prod(shape)], shape, stride, offset, Nd4j.order());
    }

    /**
     * Create the ndarray with
     * the specified shape and stride and an offset of 0
     *
     * @param shape  the shape of the ndarray
     * @param stride the stride of the ndarray
     */
    public BaseNDArray(int[] shape, int[] stride) {
        this(shape, stride, 0);
    }

    public BaseNDArray(int[] shape, int offset) {
        this(shape, calcStrides(shape), offset);
    }

    public BaseNDArray(int[] shape, char ordering) {
        this(shape, 0, ordering);
    }


    /**
     * Creates a new <i>n</i> times <i>m</i> <tt>DoubleMatrix</tt>.
     *
     * @param newRows    the number of rows (<i>n</i>) of the new matrix.
     * @param newColumns the number of columns (<i>m</i>) of the new matrix.
     */
    public BaseNDArray(int newRows, int newColumns) {
        this(newRows, newColumns, Nd4j.order());
    }


    /**
     * Create an ndarray from the specified slices.
     * This will go through and merge all of the
     * data from each slice in to one ndarray
     * which will then take the specified shape
     *
     * @param slices the slices to merge
     * @param shape  the shape of the ndarray
     */
    public BaseNDArray(List<INDArray> slices, int[] shape) {
        this(slices, shape, Nd4j.order());
    }

    /**
     * Create an ndarray from the specified slices.
     * This will go through and merge all of the
     * data from each slice in to one ndarray
     * which will then take the specified shape
     *
     * @param slices the slices to merge
     * @param shape  the shape of the ndarray
     */
    public BaseNDArray(List<INDArray> slices, int[] shape, int[] stride) {
        this(slices, shape, stride, Nd4j.order());

    }


    public BaseNDArray(float[] data, int[] shape, int[] stride) {
        this(data, shape, stride, Nd4j.order());
    }


    public BaseNDArray(float[] data, int[] shape, int[] stride, int offset) {
        this(data, shape, stride, offset, Nd4j.order());
    }


    public BaseNDArray(float[] data) {
        this.data = Nd4j.createBuffer(data);
    }


    public BaseNDArray(float[][] data) {
        this(data.length, data[0].length);

        for (int r = 0; r < rows; r++) {
            assert (data[r].length == columns);
        }

        this.data = Nd4j.createBuffer(length);


        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                putScalar(new int[]{r, c}, data[r][c]);
            }
        }
    }


    /**
     * Constructor for stride and offset
     *
     * @param buffer
     * @param shape
     * @param offset
     * @param ordering
     */
    public BaseNDArray(DataBuffer buffer, int[] shape, int offset, char ordering) {
        this(buffer, shape, Nd4j.getStrides(shape, ordering), offset, ordering);
    }

    /**
     * Mainly an internal method (public  for testing)
     * for given an offset and stride, and index,
     * calculating the beginning index of a query given indices
     *
     * @param offset  the desired offset
     * @param stride  the desired stride
     * @param indexes the desired indexes to test on
     * @return the index for a query given stride and offset
     */
    public static int getIndex(int offset, int[] stride, int... indexes) {
        if (stride.length > indexes.length)
            throw new IllegalArgumentException("Invalid number of items in stride array: should be <= number of indexes");

        int ix = offset;


        for (int i = 0; i < indexes.length; i++) {
            ix += indexes[i] * stride[i];
        }
        return ix;
    }

    /**
     * Returns whether the ndarray is valid or not
     * @return true if the ndarray is valid
     * false otherwise
     */
    public boolean isValid() {
        try {
            linearIndex(length() - 1);
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    @Override
    public INDArray linearViewColumnOrder() {
        ensureNotCleanedUp();
        return create(data, new int[]{length, 1}, offset());
    }

    protected INDArray create(DataBuffer data,int[] shape,int offset) {
        return Nd4j.create(data, shape, offset);
    }

    protected void ensureNotCleanedUp() {
        if(cleanedUp)
            throw new IllegalStateException("Invalid operation: already collected");
    }

    /**
     * Returns a linear view reference of shape
     * 1,length(ndarray)
     *
     * @return the linear view of this ndarray
     */
    @Override
    public INDArray linearView() {
        ensureNotCleanedUp();
        if (isVector())
            return this;
        if (linearView == null)
            resetLinearView();

        return linearView;
    }

    @Override
    public void resetLinearView() {
        ensureNotCleanedUp();
        if(isVector())
            linearView = this;
        linearView = new LinearViewNDArray(this);


    }

    @Override
    public void setData(float[] data) {
        ensureNotCleanedUp();
        this.data = Nd4j.createBuffer(data);
    }

    @Override
    public int elementStride() {
        return 1;
    }

    @Override
    public int majorStride() {
        ensureNotCleanedUp();
        if(majorStride >= 0)
            return majorStride;
        if(stride.length == 0) {
            majorStride = elementStride();
            return elementStride();
        }
        if(ordering() == NDArrayFactory.C) {
            if(stride[shape().length - 1] == 1 && !isMatrix()) {
                int ret =  getFirstNonOneStride();
                majorStride = ret;
                return ret;
            }
        }

        if(ordering() == NDArrayFactory.FORTRAN && size(0) == 1) {
            int ret =  getFirstNonOneStride();
            majorStride = ret;
            return ret;
        }

        int majorStride =  stride[0];
        this.majorStride = majorStride;
        return majorStride;
    }

    @Override
    public int secondaryStride() {
        ensureNotCleanedUp();
        if(stride.length == 0)
            return 1;
        if (stride.length >= 2) {
            if (ordering == NDArrayFactory.C) {
                if (isColumnVector())
                    return majorStride();
                return stride[1];
            } else
                return majorStride();
        }
        return majorStride();
    }

    /**
     * Returns the number of possible vectors for a given dimension
     *
     * @param dimension the dimension to calculate the number of vectors for
     * @return the number of possible vectors along a dimension
     */
    @Override
    public int vectorsAlongDimension(int dimension) {
        ensureNotCleanedUp();
        if(dimension == 0 && isVector() || isRowVector())
            return 1;
        if (dimension >= shape.length)
            return length / size(shape.length - 1);
        return length / size(dimension);
    }

    /**
     * Get the vector along a particular dimension
     *
     * @param index     the index of the vector to getScalar
     * @param dimension the dimension to getScalar the vector from
     * @return the vector along a particular dimension
     */
    @Override
    public INDArray vectorAlongDimension(int index, int dimension) {
        if(dimension < 0)
            dimension = stride.length + dimension;
        int vectorsAlongDimension = vectorsAlongDimension(dimension);
        if (index >= vectorsAlongDimension)
            throw new IllegalArgumentException("Index greater than possible number of vectors along dimension " + dimension);

        if(ordering() == NDArrayFactory.FORTRAN) {
            if (index >= vectorsAlongDimension)
                throw new IllegalArgumentException("Index greater than possible number of vectors along dimension " + dimension);
            if(isMatrix()) {
                if(dimension == 0) {
                    return create(data,
                            new int[]{shape[dimension],1}
                            ,  new int[]{stride[dimension],elementStride()},
                            offset + index * stride[stride.length - 1]);
                }
                else if(dimension == 1) {
                    return create(data,
                            new int[]{1, shape[dimension]}
                            , new int[]{elementStride(), stride[dimension]},
                            calcoffset(index));
                }
            }

            return create(data,
                    new int[]{1, shape[dimension]}
                    , stride[dimension] != 1 ? new int[]{stride[dimension], elementStride()} : new int[]{elementStride(),stride[dimension]},
                    calcoffset(index));

        }

        if(isMatrix()) {
            if(dimension == 0) {
                if(ordering() == NDArrayFactory.C)
                    return create(data,
                            new int[]{shape[dimension],1}
                            ,  new int[]{stride[dimension],elementStride()},
                            offset + index * stride[stride.length - 1]);
                else
                    return create(data,
                            new int[]{shape[dimension],1}
                            ,  new int[]{stride[dimension],elementStride()},
                            calcoffset(index));
            }
            else if(dimension == 1) {
                if(ordering() == NDArrayFactory.C)
                    return create(data,
                            new int[]{1, shape[dimension]}
                            , ArrayUtil.of(elementStride(),stride[dimension]),
                            calcoffset(index));
                //fortran ordering
                return create(data,
                        new int[]{1, shape[dimension]}
                        , new int[]{stride[dimension],elementStride()},
                        calcoffset(index));
            }
        }

        int arrOffset = offset + index * stride[0];

        /**
         * The offset is greater than the dimensions.
         * When doing vector along dimension
         * you will often run in to situations
         * where you need to go back up the slices.
         *
         * This will perform a reset of the offset
         * to be relative to the index of the vector
         * and set it to the appropriate offset.
         *
         * An example of this situation:
         * Shape: 4 x 3 x2
         * Stride: 6 x 2 x 1
         *
         * The array has 4 slices.
         *
         * In order to iterate through the array properly,
         * we will need to reset the offset. This situation
         * will always occur when iterating along the final dimension.
         * Given an array:
         *
         * ([[[  1.,   2.],
         [  3.,   4.],
         [  5.,   6.]],

         [[  7.,   8.],
         [  9.,  10.],
         [ 11.,  12.]],

         [[ 13.,  14.],
         [ 15.,  16.],
         [ 17.,  18.]],

         [[ 19.,  20.],
         [ 21.,  22.],
         [ 23.,  24.]]])


         Iterating along dimension 2 gives us:

         1,2
         7,8
         13,14
         19,20
         3,4
         9,10,
         15,16
         21,22,
         5,6,
         11,12
         17,18
         23,24

         You will notice that we iterate through the array by going up and down.

         This allows us to continuously iterate through an array seamlessly
         even along the last dimension.

         This algorithm adjusts the offsets wrt the index such that the
         index is placed properly when "going up" the array.


         *
         */
        if(arrOffset >= length()) {
            int numDecremented = 0;
            int startIndex = index;
            while(startIndex >= slices()) {
                numDecremented++;
                startIndex -= slices();
            }

            arrOffset = offset + ((startIndex * stride[0]) + (numDecremented * stride[1]));

        }

        return create(data,
                new int[]{1, shape[dimension]}
                , new int[]{1, stride[dimension]},
                arrOffset);

    }


    private int getFirstNonOneStrideIdx() {
        for(int j = 0; j < stride().length; j++) {
            if(stride[j] == 1)
                continue;

            return j;
        }

        return elementStride();

    }
    private int getFirstNonOneStride() {
        if(firstNonOneStride >= 0)
            return firstNonOneStride;

        for(int j = 0; j < stride().length; j++) {
            if(stride()[j] == 1)
                continue;
            if(firstNonOneStride < 0)
                firstNonOneStride = stride[j];
            return stride[j];
        }

        if(firstNonOneStride < 0)
            firstNonOneStride = elementStride();
        return elementStride();

    }

    /**
     * Cumulative sum along a dimension
     *
     * @param dimension the dimension to perform cumulative sum along
     * @return the cumulative sum along the specified dimension
     */
    @Override
    public INDArray cumsumi(int dimension) {
        ensureNotCleanedUp();
        if (isVector()) {
            double s = 0.0;
            for (int i = 0; i < length; i++) {
                s += getDouble(i);
                putScalar(i, s);
            }
        } else if (dimension == Integer.MAX_VALUE) {
            INDArray flattened = ravel();
            double prevVal = flattened.getDouble(0);
            for (int i = 1; i < flattened.length(); i++) {
                double d = prevVal + flattened.getDouble(i);
                flattened.putScalar(i, d);
                prevVal = d;
            }

            return flattened;
        } else {
            for (int i = 0; i < vectorsAlongDimension(dimension); i++) {
                INDArray vec = vectorAlongDimension(i, dimension);
                vec.cumsumi(0);

            }
        }


        return this;
    }

    /**
     * Cumulative sum along a dimension (in place)
     *
     * @param dimension the dimension to perform cumulative sum along
     * @return the cumulative sum along the specified dimension
     */
    @Override
    public INDArray cumsum(int dimension) {
        ensureNotCleanedUp();
        return dup().cumsumi(dimension);
    }

    /**
     * Assign all of the elements in the given
     * ndarray to this ndarray
     *
     * @param arr the elements to assign
     * @return this
     */
    @Override
    public INDArray assign(INDArray arr) {
        ensureNotCleanedUp();
        if (!arr.isVector() && !isVector())
            LinAlgExceptions.assertSameShape(this, arr);
        else if (isVector() && arr.isVector() && length() != arr.length())
            throw new IllegalArgumentException("Illegal assignment, must be of same length");
        INDArray linear = arr.linearView();
        INDArray thisLinear = linearView();
        for(int i = 0; i < linear.length(); i++)
            thisLinear.putScalar(i,linear.getDouble(i));
        return this;
    }

    @Override
    public INDArray putScalar(int i, double value) {
        ensureNotCleanedUp();
        int idx = linearIndex(i);
        data.put(idx, value);
        return this;
    }

    @Override
    public INDArray putScalar(int i, float value) {
        return putScalar(i,(double) value);

    }

    @Override
    public INDArray putScalar(int i, int value) {
        return putScalar(i, (double) value);
    }

    @Override
    public INDArray putScalar(int[] indexes, double value) {
        ensureNotCleanedUp();
        if(isRowVector() && indexes.length == 2 && indexes[0] == 0) {
            int ix = linearIndex(indexes[1]);
            data.put(ix,value);
        }
        else {
            int ix = offset;
            for (int i = 0; i < shape.length; i++) {
                ix += indexes[i] * stride[i];
            }
            if (ix >= data.length())
                throw new IllegalArgumentException("Illegal indices " + Arrays.toString(indexes));
            data.put(ix, value);
        }


        return this;
    }

    @Override
    public INDArray putScalar(int[] indexes, float value) {
        ensureNotCleanedUp();
        return putScalar(indexes, (double) value);
    }

    @Override
    public INDArray putScalar(int[] indexes, int value) {
        ensureNotCleanedUp();
        return putScalar(indexes, (double) value);
    }

    /**
     * Returns an ndarray with 1 if the element is epsilon equals
     *
     * @param other the number to compare
     * @return a copied ndarray with the given
     * binary conditions
     */
    @Override
    public INDArray eps(Number other) {
        return dup().epsi(other);
    }

    /**
     * Returns an ndarray with 1 if the element is epsilon equals
     *
     * @param other the number to compare
     * @return a copied ndarray with the given
     * binary conditions
     */
    @Override
    public INDArray epsi(Number other) {
        ensureNotCleanedUp();
        INDArray linearView = linearView();
        double otherVal = other.doubleValue();

        for (int i = 0; i < linearView.length(); i++) {
            double val = linearView.getDouble(i);
            double diff = Math.abs(val - otherVal);
            if (diff <= Nd4j.EPS_THRESHOLD)
                linearView.putScalar(i, 1);
            else
                linearView.putScalar(i, 0);

        }
        return this;
    }

    /**
     * epsilon equals than comparison:
     * If the given number is less than the
     * comparison number the item is 0 otherwise 1
     *
     * @param other the number to compare
     * @return
     */
    @Override
    public INDArray eps(INDArray other) {
        return dup().epsi(other);
    }

    /**
     * In place epsilon equals than comparison:
     * If the given number is less than the
     * comparison number the item is 0 otherwise 1
     *
     * @param other the number to compare
     * @return
     */
    @Override
    public INDArray epsi(INDArray other) {
        ensureNotCleanedUp();
        Nd4j.getExecutioner().exec(new Eps(linearView(), other.linearView(), this, length()));
        return this;
    }

    @Override
    public INDArray lt(Number other) {
        return dup().lti(other);
    }

    @Override
    public INDArray lti(Number other) {
        ensureNotCleanedUp();
        Nd4j.getExecutioner().exec(new ScalarLessThan(linearView(), other));
        return this;
    }

    @Override
    public INDArray eq(Number other) {
        return dup().eqi(other);
    }

    @Override
    public INDArray eqi(Number other) {
        ensureNotCleanedUp();
        Nd4j.getExecutioner().exec(new ScalarEquals(linearView(), other));
        return this;
    }

    @Override
    public INDArray gt(Number other) {
        return dup().gti(other);
    }

    @Override
    public INDArray gti(Number other) {
        ensureNotCleanedUp();
        Nd4j.getExecutioner().exec(new ScalarGreaterThan(linearView(), other));
        return this;
    }

    @Override
    public INDArray lt(INDArray other) {
        return dup().lti(other);
    }

    @Override
    public INDArray lti(INDArray other) {
        ensureNotCleanedUp();
        Nd4j.getExecutioner().exec(new LessThan(linearView(), other, linearView(), length()));
        return this;
    }

    @Override
    public INDArray neq(Number other) {
        return dup().neqi(other);
    }

    @Override
    public INDArray neqi(Number other) {
        ensureNotCleanedUp();
        Nd4j.getExecutioner().exec(new ScalarNotEquals(linearView(), other));
        return this;
    }

    @Override
    public INDArray neq(INDArray other) {
        return dup().neqi(other);
    }

    @Override
    public INDArray neqi(INDArray other) {
        ensureNotCleanedUp();
        Nd4j.getExecutioner().exec(new NotEqualTo(linearView(), other.linearView(), linearView(), length()));
        return this;
    }

    @Override
    public INDArray eq(INDArray other) {
        return dup().eqi(other);
    }

    @Override
    public INDArray eqi(INDArray other) {
        ensureNotCleanedUp();
        Nd4j.getExecutioner().exec(new EqualTo(linearView(), other.linearView(), linearView(), length()));
        return this;
    }

    @Override
    public INDArray gt(INDArray other) {
        return dup().gti(other);
    }

    @Override
    public INDArray gti(INDArray other) {
        ensureNotCleanedUp();
        Nd4j.getExecutioner().exec(new GreaterThan(linearView(), other.linearView(), linearView(), length()));
        return this;
    }

    /**
     * Negate each element.
     */
    @Override
    public INDArray neg() {
        return dup().negi();
    }

    /**
     * Negate each element (in-place).
     */
    @Override
    public INDArray negi() {
        ensureNotCleanedUp();
        Nd4j.getExecutioner().exec(new Negative(linearView()));
        return this;
    }

    @Override
    public INDArray rdiv(Number n, INDArray result) {
        return dup().rdivi(n, result);
    }

    @Override
    public INDArray rdivi(Number n, INDArray result) {
        ensureNotCleanedUp();
        if (Double.isNaN(n.doubleValue()))
            n = Nd4j.EPS_THRESHOLD;
        Nd4j.getExecutioner().exec(new ScalarReverseDivision(linearView(), null, result.linearView(), result.length(), n));
        if (Nd4j.ENFORCE_NUMERICAL_STABILITY)
            Nd4j.clearNans(result);
        return result;
    }

    @Override
    public INDArray rsub(Number n, INDArray result) {
        return dup().rsubi(n, result);
    }

    @Override
    public INDArray rsubi(Number n, INDArray result) {
        ensureNotCleanedUp();
        if (Double.isNaN(n.doubleValue()))
            n = Nd4j.EPS_THRESHOLD;

        Nd4j.getExecutioner().exec(new ScalarReverseSubtraction(linearView(), null, result.linearView(), result.length(), n));

        if (Nd4j.ENFORCE_NUMERICAL_STABILITY)
            Nd4j.clearNans(result);
        return result;
    }

    @Override
    public INDArray div(Number n, INDArray result) {
        return dup().divi(n, result);
    }

    @Override
    public INDArray divi(Number n, INDArray result) {
        ensureNotCleanedUp();
        if (Double.isNaN(n.doubleValue()))
            n = Nd4j.EPS_THRESHOLD;
        Nd4j.getExecutioner().exec(new ScalarDivision(linearView(), null, result.linearView(), result.length(), n));


        if (Nd4j.ENFORCE_NUMERICAL_STABILITY)
            Nd4j.clearNans(result);

        return result;
    }

    @Override
    public INDArray mul(Number n, INDArray result) {
        return dup().muli(n, result);
    }

    @Override
    public INDArray muli(Number n, INDArray result) {
        ensureNotCleanedUp();
        if (Double.isNaN(n.doubleValue()))
            n = Nd4j.EPS_THRESHOLD;
        Nd4j.getExecutioner().exec(new ScalarMultiplication(linearView(), null, result.linearView(), result.length(), n));

        if (Nd4j.ENFORCE_NUMERICAL_STABILITY)
            Nd4j.clearNans(result);

        return result;
    }

    @Override
    public INDArray sub(Number n, INDArray result) {
        return dup().subi(n, result);
    }

    @Override
    public INDArray subi(Number n, INDArray result) {
        ensureNotCleanedUp();
        if (Double.isNaN(n.doubleValue()))
            n = Nd4j.EPS_THRESHOLD;

        Nd4j.getExecutioner().exec(new ScalarSubtraction(linearView(), null, result.linearView(), result.length(), n));
        if (Nd4j.ENFORCE_NUMERICAL_STABILITY)
            Nd4j.clearNans(result);

        return result;
    }

    @Override
    public INDArray add(Number n, INDArray result) {
        return dup().addi(n, result);
    }

    @Override
    public INDArray addi(Number n, INDArray result) {
        ensureNotCleanedUp();
        if (Double.isNaN(n.doubleValue()))
            n = Nd4j.EPS_THRESHOLD;
        Nd4j.getExecutioner().exec(new ScalarAdd(linearView(), null, result.linearView(), result.length(), n));
        return this;
    }



    /**
     * Returns the element at the specified row/column
     * This will throw an exception if the
     *
     * @param row    the row of the element to return
     * @param column the row of the element to return
     * @return a scalar indarray of the element at this index
     */
    @Override
    public INDArray getScalar(int row, int column) {
        return getScalar(new int[]{row, column});
    }

    @Override
    public INDArray dup() {
        ensureNotCleanedUp();
        INDArray ret = create(data.dup(), shape(),stride(),offset());
        return ret;
    }

    /**
     * Returns the elements at the the specified indices
     *
     * @param indices the indices to getScalar
     * @return the array with the specified elements
     */
    @Override
    public int getInt(int... indices) {
        ensureNotCleanedUp();
        int ix = offset;
        for (int i = 0; i < indices.length; i++)
            ix += indices[i] * stride[i];

        return data.getInt(ix);

    }

    /**
     * Returns the elements at the the specified indices
     *
     * @param indices the indices to get
     * @return the array with the specified elements
     */
    @Override
    public double getDouble(int... indices) {
        ensureNotCleanedUp();
        if(isRowVector() && indices[0] == 0 && indices.length == 2) {
            int ix = linearIndex(indices[1]);
            return data.getDouble(ix);
        }

        else {
            int ix = offset;
            for (int i = 0; i < indices.length; i++)
                ix += indices[i] * stride[i];

            return data.getDouble(ix);
        }


    }

    /**
     * Returns the elements at the the specified indices
     *
     * @param indices the indices to getScalar
     * @return the array with the specified elements
     */
    @Override
    public float getFloat(int... indices) {
        ensureNotCleanedUp();
        return (float) getDouble(indices);
    }

    /**
     * Test whether a matrix is scalar.
     */
    @Override
    public boolean isScalar() {
        if(isScalar != null)
            return isScalar;
        ensureNotCleanedUp();
        if (shape.length == 0) {
            isScalar = true;
            return true;
        }
        else if (shape.length == 1 && shape[0] == 1) {
            isScalar = true;
            return true;
        }
        else if (shape.length >= 2) {
            for (int i = 0; i < shape.length; i++)
                if (shape[i] != 1) {
                    isScalar = false;
                    return false;
                }
        }

        isScalar = length == 1;
        return isScalar;
    }

    /**
     * Inserts the element at the specified index
     *
     * @param indices the indices to insert into
     * @param element a scalar ndarray
     * @return a scalar ndarray of the element at this index
     */
    @Override
    public INDArray put(int[] indices, INDArray element) {
        ensureNotCleanedUp();
        if (!element.isScalar())
            throw new IllegalArgumentException("Unable to insert anything but a scalar");
        if(isRowVector() && indices[0] == 0 && indices.length == 2) {
            int ix = offset;
            for (int i = 1; i < indices.length; i++)
                ix += indices[i] * stride[i];
            if (ix >= data.length())
                throw new IllegalArgumentException("Illegal indices " + Arrays.toString(indices));
            data.put(ix, element.getDouble(0));
        }
        else {
            int ix = offset;
            for (int i = 0; i < indices.length; i++)
                ix += indices[i] * stride[i];
            if (ix >= data.length())
                throw new IllegalArgumentException("Illegal indices " + Arrays.toString(indices));
            data.put(ix, element.getDouble(0));
        }


        return this;

    }

    /**
     * Inserts the element at the specified index
     *
     * @param i       the row insert into
     * @param j       the column to insert into
     * @param element a scalar ndarray
     * @return a scalar ndarray of the element at this index
     */
    @Override
    public INDArray put(int i, int j, INDArray element) {
        return put(new int[]{i, j}, element);
    }

    /**
     * Inserts the element at the specified index
     *
     * @param i       the row insert into
     * @param j       the column to insert into
     * @param element a scalar ndarray
     * @return a scalar ndarray of the element at this index
     */
    @Override
    public INDArray put(int i, int j, Number element) {
        ensureNotCleanedUp();
        return putScalar(new int[]{i, j}, element.doubleValue());
    }

    /**
     * Assigns the given matrix (put) to the specified slice
     *
     * @param slice the slice to assign
     * @param put   the slice to put
     * @return this for chainability
     */
    @Override
    public INDArray putSlice(int slice, INDArray put) {
        ensureNotCleanedUp();
        if (isScalar()) {
            assert put.isScalar() : "Invalid dimension. Can only insert a scalar in to another scalar";
            put(0, put.getScalar(0));
            return this;
        } else if (isVector()) {
            assert put.isScalar() || put.isVector() &&
                    put.length() == length() : "Invalid dimension on insertion. Can only insert scalars input vectors";
            if (put.isScalar())
                putScalar(slice, put.getDouble(0));
            else
                for (int i = 0; i < length(); i++)
                    putScalar(i, put.getDouble(i));

            return this;
        }

        assertSlice(put, slice);


        INDArray view = slice(slice);

        if (put.isScalar())
            putScalar(slice, put.getDouble(0));
        else if (put.isVector())
            for (int i = 0; i < put.length(); i++)
                view.putScalar(i, put.getDouble(i));
        else {

            assert Shape.shapeEquals(view.shape(),put.shape());
            INDArray linear = view.linearView();
            INDArray putLinearView = put.linearView();
            for(int i = 0; i < linear.length(); i++) {
                linear.putScalar(i,putLinearView.getDouble(i));
            }


        }

        return this;

    }

    protected void assertSlice(INDArray put, int slice) {
        ensureNotCleanedUp();
        assert slice <= slices() : "Invalid slice specified " + slice;
        int[] sliceShape = put.shape();
        if(Shape.isRowVectorShape(sliceShape)) {
            return;
        }
        else {
            int[] requiredShape = ArrayUtil.removeIndex(shape(), 0);

            //no need to compare for scalar; primarily due to shapes either being [1] or length 0
            if (put.isScalar())
                return;

            if(isVector() && put.isVector() && put.length() < length())
                return;
            //edge case for column vectors
            if (Shape.isColumnVectorShape(sliceShape))
                return;
            if(!Shape.shapeEquals(sliceShape, requiredShape) && !Shape.isRowVectorShape(requiredShape) && !Shape.isRowVectorShape(sliceShape))
                throw new IllegalStateException(String.format("Invalid shape size of %s . Should have been %s "
                        , Arrays.toString(sliceShape), Arrays.toString(requiredShape)));

        }

    }

    /**
     * Returns true if this ndarray is 2d
     * or 3d with a singleton element
     *
     * @return true if the element is a matrix, false otherwise
     */
    public boolean isMatrix() {
        ensureNotCleanedUp();
        return (shape().length == 2
                && (shape[0] != 1 && shape[1] != 1));
    }


    @Override
    public int index(int row, int column) {
        ensureNotCleanedUp();
        if (!isMatrix()) {
            if (isColumnVector()) {
                int idx = linearIndex(row);
                return idx;
            } else if (isRowVector()) {
                int idx = linearIndex(column);
                return idx;
            }
            else
                throw new IllegalStateException("Unable to get row/column from a non matrix");
        }


        return offset + (row * stride[0] + column * stride[1]);
    }

    protected INDArray newShape(int[] newShape, char ordering) {
        ensureNotCleanedUp();
        if (Arrays.equals(newShape, this.shape()))
            return this;

        else if (Shape.isVector(newShape) && isVector()) {
            if (isRowVector() && Shape.isColumnVectorShape(newShape)) {
                int[] stride = ordering() == NDArrayFactory.C ? ArrayUtil.copy(stride()) : getStrides(new int[]{columns(),1},ordering());
                return create(data, newShape,stride,offset);
            }
            //handle case where row vector is reshaped to row vector
            else if(isRowVector() && newShape.length == 1 || isRowVector() && newShape.length == 2) {
                this.shape = newShape;
            }

            else if (isColumnVector() && Shape.isRowVectorShape(newShape)) {
                return create(data, newShape, new int[]{stride[0],1}, offset);

            }
        }

        INDArray newCopy = this;
        int[] newStrides = null;
        //needed to copy data
        if (newStrides == null) {
            //need new strides for shape (the first stride messes with it)
            newStrides = this instanceof IComplexNDArray ? Nd4j.getComplexStrides(newShape, ordering()) : Nd4j.getStrides(newShape, ordering());
        }

        //create a new copy of the ndarray
        if (shape().length > 1 || this.ordering != ordering || newShape.length != shape().length) {
            if(this instanceof IComplexNDArray)
                return  create(data,newShape,newStrides,offset,ordering);
            newCopy = create(data, newShape, newStrides, offset, ordering);
            return newCopy;
        }

        return create(newCopy.data(), newShape, newStrides, offset);


    }

    protected INDArray create(DataBuffer data, int[] newShape, int[] newStrides, int offset, char ordering) {
        return Nd4j.create(data, newShape, newStrides, offset, ordering);
    }

    protected INDArray create(DataBuffer data, int[] newShape, int[] newStrides, int offset) {
        return Nd4j.create(data, newShape, newStrides, offset);
    }

    protected INDArray create(int[] shape) {
        return Nd4j.create(shape, getStrides(shape, ordering), 0);
    }

    protected INDArray create(int[] shape,int[] strides,int offset) {
        return Nd4j.create(shape, strides, offset);
    }

    protected int[] getStrides(int[] shape,char ordering) {
        return Nd4j.getStrides(shape, ordering);
    }


    /**
     * Return the new strides based on the shape and ordering or null
     * if we can't do a reshape
     *
     * @param newShape the new shape
     * @param ordering the ordering of the new shape
     * @return the new strides or null if we can't reshape
     */
    protected int[] noCopyReshape(int[] newShape, char ordering) {
        ensureNotCleanedUp();
        List<Integer> oldDims = new ArrayList<>();
        List<Integer> oldStrides = new ArrayList<>();
        for (int i = 0; i < shape.length; i++) {
            if (size(i) != 1) {
                oldDims.add(size(i));
                oldStrides.add(stride[i]);
            }
        }

        int np = 1;
        for (int ni = 0; ni < newShape.length; ni++) {
            np *= newShape[ni];
        }

        int op = 1;
        for (int oi = 0; oi < oldDims.size(); oi++) {
            op *= oldDims.get(oi);
        }
        if (np != op) {
        /* different total sizes; no hope */
            return null;
        }

        if (np == 0) {
        /* the current code does not handle 0-sized arrays, so give up */
            return null;
        }


          /* oi to oj and ni to nj give the axis ranges currently worked with */
        int oi = 0;
        int oj = 1;
        int ni = 0;
        int nj = 1;

        List<Integer> newStrides = new ArrayList<>();
        while (ni < newShape.length && oi < oldDims.size()) {
            np = newShape[ni];
            op = oldDims.get(oi);

            while (np != op) {
                if (np < op)
                    np *= newShape[nj++];
                else
                    op *= oldDims.get(oj++);
            }

                    /* Check whether the original axes can be combined */
            for (int ok = oi; ok < oj - 1; ok++) {
                if (ordering == NDArrayFactory.FORTRAN) {
                    if (oldStrides.get(ok + 1) != oldDims.get(ok) * oldStrides.get(ok)) {
                     /* not contiguous enough */
                        return null;
                    }
                } else {
                /* C order */
                    if (oldStrides.get(ok) != oldDims.get(ok + 1) * oldStrides.get(ok + 1)) {
                    /* not contiguous enough */
                        return null;
                    }
                }
            }


             /* Calculate new strides for all axes currently worked with */
            if (ordering == NDArrayFactory.FORTRAN) {
                if(newStrides.size() < oldStrides.size()) {
                    int delta = Math.abs(newStrides.size() - oldStrides.size());
                    for(int i = 0; i < delta; i++)
                        newStrides.add(elementStride());
                }
                newStrides.set(ni, oldStrides.get(oi));
                for (int nk = ni + 1; nk < nj; nk++) {
                    int get = newStrides.get(nk - 1) * newShape[nk - 1];
                    if(nk >= newStrides.size()) {
                        int delta = Math.abs(nk - newStrides.size() + 1);
                        for(int i = 0; i < delta; i++)
                            newStrides.add(elementStride());
                        newStrides.add(elementStride());
                    }
                    newStrides.set(nk, get);
                }
            } else {
            /* C order */
                newStrides.set(nj - 1, oldStrides.get(oj - 1));

                for (int nk = nj - 1; nk > ni; nk--) {
                    newStrides.set(nk - 1, newStrides.get(nk) * newShape[nk]);
                }
            }
            ni = nj++;
            oi = oj++;
        }

    /*
     * Set strides corresponding to trailing 1s of the new shape.
     */
        int lastStride;
        if (ni >= 1) {
            lastStride = newStrides.get(ni - 1);
        } else {
            lastStride = length;
        }
        if (ordering == NDArrayFactory.FORTRAN) {
            lastStride *= newShape[ni - 1];
        }
        for (int nk = ni; nk < newShape.length; nk++) {
            newStrides.set(nk, lastStride);
        }


        return ArrayUtil.toArray(newStrides);


    }


    /**
     * Returns the squared (Euclidean) distance.
     */
    @Override
    public double squaredDistance(INDArray other) {
        ensureNotCleanedUp();
        double sd = 0.0;
        for (int i = 0; i < length; i++) {
            double d = getDouble(i) - other.getDouble(i);
            sd += d * d;
        }
        return sd;
    }

    /**
     * Returns the (euclidean) distance.
     */
    @Override
    public double distance2(INDArray other) {
        ensureNotCleanedUp();
        return Math.sqrt(squaredDistance(other));
    }

    /**
     * Returns the (1-norm) distance.
     */
    @Override
    public double distance1(INDArray other) {
        ensureNotCleanedUp();
        return other.sub(this).sum(Integer.MAX_VALUE).getDouble(0);
    }

    @Override
    public INDArray put(NDArrayIndex[] indices, INDArray element) {
        if (isVector()) {
            assert element.isScalar() || element.isVector() : "Unable to assign elements. Element is not a vector.";
            assert element.isScalar() || indices[0].length() <= element.length() : "Number of specified elements in index does not match length of element.";
            int[] assign = indices[0].indices();
            if(indices[0] instanceof NDArrayIndex.NDArrayIndexAll)
                assign = NDArrayIndex.interval(0,length()).indices();
            if(element.isScalar()) {
                for (int i = 0; i < assign.length; i++) {
                    putScalar(assign[i], element.getDouble(0));
                }
            }
            else {
                for (int i = 0; i < assign.length; i++) {
                    putScalar(assign[i], element.getDouble(i));
                }
            }


            return this;

        }

        if (element.isVector()) {
            if(indices[0] instanceof NDArrayIndex.NDArrayIndexAll) {
                assign(element);
            }
            else {
                slice(indices[0].indices()[0]).put(Arrays.copyOfRange(indices, 1, indices.length), element);

            }

        }
        else if(isMatrix() && element.isMatrix()) {
            NDArrayIndex[] columns = Arrays.copyOfRange(indices,1,indices.length);
            for(int i = 0; i < element.rows(); i++) {
                slice(i).put(columns,element.slice(i));
            }
        }

        else {
            NDArrayIndex[] indicesSub = Arrays.copyOfRange(indices,1,indices.length);

            for (int i = 0; i < element.slices(); i++) {
                slice(i).put(indicesSub,element.slice(i));
            }
        }



        return this;
    }

    @Override
    public INDArray put(NDArrayIndex[] indices, Number element) {
        return put(indices, createScalar(element.doubleValue()));
    }


    /**
     * Mainly here for people coming from numpy.
     * This is equivalent to a call to permute
     *
     * @param dimension the dimension to swap
     * @param with      the one to swap it with
     * @return the swapped axes view
     */
    @Override
    public INDArray swapAxes(int dimension, int with) {
        ensureNotCleanedUp();
        int[] shape = ArrayUtil.range(0, shape().length);
        shape[dimension] = with;
        shape[with] = dimension;
        return permute(shape);
    }

    /**
     * Gives the indices for the ending of each slice
     *
     * @return the off sets for the beginning of each slice
     */
    @Override
    public int[] endsForSlices() {
        ensureNotCleanedUp();
        int[] ret = new int[slices()];
        int currOffset = offset + stride[0] - 1;
        for (int i = 0; i < slices(); i++) {
            ret[i] = currOffset;
            currOffset += stride[0];
        }
        return ret;
    }

    @Override
    public DataBuffer data() {
        ensureNotCleanedUp();
        return data;
    }

    @Override
    public void setData(DataBuffer data) {
        ensureNotCleanedUp();
        this.data = data;
    }

    /**
     * Number of slices: aka shape[0]
     *
     * @return the number of slices
     * for this nd array
     */
    @Override
    public int slices() {
        ensureNotCleanedUp();
        if (shape.length < 1)
            return 0;
        return shape[0];
    }

    @Override
    public INDArray subArray(int[] offsets, int[] shape, int[] stride) {
        ensureNotCleanedUp();
        int n = shape.length;
        if (shape.length < 1)
            return create(Nd4j.createBuffer(shape));
        if (offsets.length != n)
            throw new IllegalArgumentException("Invalid offset " + Arrays.toString(offsets));
        if (shape.length != n)
            throw new IllegalArgumentException("Invalid shape " + Arrays.toString(shape));

        if (Arrays.equals(shape, this.shape)) {
            if (ArrayUtil.isZero(offsets)) {
                return this;
            } else {
                throw new IllegalArgumentException("Invalid subArray offsets");
            }
        }



        /**
         * Any situation where the second row vector offset is 0
         * will cause the dot product to screw up.
         *
         * This will set those offsets beyond the first to be 1.
         */
        boolean adjustOffsets = false;
        if(offsets.length > 1 && Shape.isRowVectorShape(shape) && offsets[0] > 0) {
            for(int i = 1; i < offsets.length; i++) {
                if(offsets[i] == 0) {
                    adjustOffsets = true;
                    break;
                }
            }

            if(adjustOffsets) {
                for(int i = 1; i < offsets.length; i++) {
                    if(offsets[i] < 1)
                        offsets[i] = 1;
                }
            }
        }


        int offset = this.offset + ArrayUtil.dotProduct(offsets, this.stride);

        //prevent off by 1
        if(adjustOffsets)
            offset--;

        if(ordering() == NDArrayFactory.C ) {
            return create(
                    data
                    , Arrays.copyOf(shape, shape.length)
                    , ArrayUtil.reverseCopy(stride)
                    , offset, ordering
            );
        }
        else if(ordering() == NDArrayFactory.FORTRAN) {
            return create(
                    data
                    , Arrays.copyOf(shape, shape.length)
                    , stride
                    , offset, ordering
            );
        }
        throw new IllegalStateException("Illegal ordering");

    }

    protected INDArray create(DataBuffer buffer) {
        return create(buffer);
    }

    @Override
    public INDArray cond(Condition condition) {
        return dup().condi(condition);
    }

    @Override
    public INDArray condi(Condition condition) {
        ensureNotCleanedUp();
        INDArray linear = linearView();
        for (int i = 0; i < length(); i++) {
            boolean met = condition.apply(linear.getDouble(i));
            linear.putScalar(i, met ? 1 : 0);
        }
        return this;
    }



    @Override
    public void setStride(int[] stride) {
        ensureNotCleanedUp();
        this.stride = stride;
    }

    protected void init(int[] shape) {
        ensureNotCleanedUp();
        this.shape = shape;

        if (this.shape.length == 1) {
            rows = 1;
            columns = this.shape[0];
        }
        else if (this.shape().length == 2) {
            rows = shape[0];
            columns = shape[1];
        }

        //default row vector
        if (this.shape.length == 1) {
            init(new int[]{1,this.shape[0]});
        }

        //null character
        if (this.ordering == '\u0000')
            this.ordering = Nd4j.order();

        this.length = ArrayUtil.prod(this.shape);
        if (this.stride == null) {
            this.stride = getStrides(shape,ordering());
        }


        //recalculate stride: this should only happen with row vectors
        if (this.stride.length != this.shape.length) {
            this.stride = getStrides(shape,ordering());
        }

    }


    @Override
    public INDArray getScalar(int i) {
        ensureNotCleanedUp();
        if (!isVector() && !isScalar())
            throw new IllegalArgumentException("Unable to do linear indexing with dimensions greater than 1");
        int idx = linearIndex(i);
        if (idx >= data.length())
            throw new IllegalArgumentException("Illegal indices " + i);
        return createScalarForIndex(idx,false);
    }

    protected void assertColumnVector(INDArray column) {
        assert column.isColumnVector() || column.columns() == columns() && column.rows() == 1 : "Must only add a column vector";
        assert column.length() == rows() || column.columns() == columns() && column.rows() == 1 : "Illegal column vector must have the same length as the number of rows in this ndarray";

    }

    /**
     * Do a row wise op (a,s,m,d)
     * a : add
     * s : subtract
     * m : multiply
     * d : divide
     * h : reverse subtraction
     * t : reverse division
     *
     * @param columnVector the column  vector
     * @param operation    the operation
     * @return
     */
    protected INDArray doColumnWise(INDArray columnVector, char operation) {
        ensureNotCleanedUp();
        if (rows() == 1 && columnVector.isScalar()) {
            switch (operation) {
                case 'a':
                    addi(columnVector.getDouble(0));
                    break;
                case 's':
                    subi(columnVector.getDouble(0));
                    break;
                case 'm':
                    muli(columnVector.getDouble(0));
                    break;
                case 'd':
                    divi(columnVector.getDouble(0));
                    break;
                case 'h':
                    rsubi(columnVector.getDouble(0));
                    break;
                case 't':
                    rdivi(columnVector.getDouble(0));
                    break;
            }

            return this;
        }
        assertColumnVector(columnVector);
        for (int i = 0; i < columns(); i++) {
            INDArray slice = getColumn(i);
            switch (operation) {

                case 'a':
                    slice.addi(columnVector);
                    break;
                case 's':
                    slice.subi(columnVector);
                    break;
                case 'm':
                    slice.muli(columnVector);
                    break;
                case 'd':
                    slice.divi(columnVector);
                    break;
                case 'h':
                    slice.rsubi(columnVector);
                    break;
                case 't':
                    slice.rdivi(columnVector);
                    break;
            }
        }

        return this;


    }

    @Override
    public boolean isCleanedUp() {
        return cleanedUp;
    }

    @Override
    public  void cleanup() {
        if (Nd4j.shouldInstrument)
            Nd4j.getInstrumentation().log(this, Instrumentation.DESTROYED);
        cleanedUp = true;
    }

    protected void assertRowVector(INDArray rowVector) {
        ensureNotCleanedUp();
        assert rowVector.isRowVector() || rowVector.rows() == rows() && rowVector.columns() == 1 : "Must only add a row vector";
        assert rowVector.length() == columns() || rowVector.rows() == rows() && rowVector.columns() == 1 : "Illegal row vector must have the same length as the number of rows in this ndarray";

    }

    /**
     * Do a row wise op (a,s,m,d)
     * a : add
     * s : subtract
     * m : multiply
     * d : divide
     * h : reverse subtraction
     * t : reverse division
     *
     * @param rowVector the row vector
     * @param operation the operation
     * @return
     */
    protected INDArray doRowWise(INDArray rowVector, char operation) {
        ensureNotCleanedUp();
        if (columns() == 1 && rowVector.isScalar()) {
            switch (operation) {
                case 'a':
                    addi(rowVector.getDouble(0));
                    break;
                case 's':
                    subi(rowVector.getDouble(0));
                    break;
                case 'm':
                    muli(rowVector.getDouble(0));
                    break;
                case 'd':
                    divi(rowVector.getDouble(0));
                    break;
                case 'h':
                    rsubi(rowVector.getDouble(0));
                    break;
                case 't':
                    rdivi(rowVector.getDouble(0));
                    break;
            }

            return this;
        }

        assertRowVector(rowVector);
        for (int i = 0; i < rows(); i++) {
            switch (operation) {

                case 'a':
                    getRow(i).addi(rowVector);
                    break;
                case 's':
                    getRow(i).subi(rowVector);
                    break;
                case 'm':
                    getRow(i).muli(rowVector);
                    break;
                case 'd':
                    getRow(i).divi(rowVector);
                    break;
                case 'h':
                    getRow(i).rsubi(rowVector);
                    break;
                case 't':
                    getRow(i).rdivi(rowVector);
                    break;
            }
        }


        return this;

    }

    @Override
    public int stride(int dimension) {
        if(dimension < 0)
            return stride[stride.length -1 + dimension];
        return stride[dimension];
    }

    @Override
    public INDArray rdiviColumnVector(INDArray columnVector) {
        return doColumnWise(columnVector, 't');
    }

    @Override
    public INDArray rdivColumnVector(INDArray columnVector) {
        return dup().rdiviColumnVector(columnVector);
    }

    @Override
    public INDArray rdiviRowVector(INDArray rowVector) {
        return doRowWise(rowVector, 't');
    }

    @Override
    public INDArray rdivRowVector(INDArray rowVector) {
        return dup().rdiviRowVector(rowVector);
    }

    @Override
    public INDArray rsubiColumnVector(INDArray columnVector) {
        return doColumnWise(columnVector, 'h');
    }

    @Override
    public INDArray rsubColumnVector(INDArray columnVector) {
        return dup().rsubiColumnVector(columnVector);
    }

    @Override
    public INDArray rsubiRowVector(INDArray rowVector) {
        return doRowWise(rowVector, 'h');
    }

    @Override
    public INDArray rsubRowVector(INDArray rowVector) {
        return dup().rsubiRowVector(rowVector);
    }

    /**
     * Inserts the element at the specified index
     *
     * @param i       the index insert into
     * @param element a scalar ndarray
     * @return a scalar ndarray of the element at this index
     */
    @Override
    public INDArray put(int i, INDArray element) {
        ensureNotCleanedUp();
        if (element == null)
            throw new IllegalArgumentException("Unable to insert null element");
        assert element.isScalar() : "Unable to insert non scalar element";

        int idx = linearIndex(i);
        if (idx >= data.length())
            throw new IllegalArgumentException("Illegal indices " + i);
        data.put(idx, element.getDouble(0));
        return this;
    }

    /**
     * In place addition of a column vector
     *
     * @param columnVector the column vector to add
     * @return the result of the addition
     */
    @Override
    public INDArray diviColumnVector(INDArray columnVector) {
        return doColumnWise(columnVector, 'd');
    }

    /**
     * In place addition of a column vector
     *
     * @param columnVector the column vector to add
     * @return the result of the addition
     */
    @Override
    public INDArray divColumnVector(INDArray columnVector) {
        return dup().diviColumnVector(columnVector);
    }

    /**
     * In place addition of a column vector
     *
     * @param rowVector the column vector to add
     * @return the result of the addition
     */
    @Override
    public INDArray diviRowVector(INDArray rowVector) {
        return doRowWise(rowVector, 'd');
    }

    /**
     * In place addition of a column vector
     *
     * @param rowVector the column vector to add
     * @return the result of the addition
     */
    @Override
    public INDArray divRowVector(INDArray rowVector) {
        return dup().diviRowVector(rowVector);
    }

    /**
     * In place addition of a column vector
     *
     * @param columnVector the column vector to add
     * @return the result of the addition
     */
    @Override
    public INDArray muliColumnVector(INDArray columnVector) {
        return doColumnWise(columnVector, 'm');
    }

    /**
     * In place addition of a column vector
     *
     * @param columnVector the column vector to add
     * @return the result of the addition
     */
    @Override
    public INDArray mulColumnVector(INDArray columnVector) {
        return dup().muliColumnVector(columnVector);
    }

    /**
     * In place addition of a column vector
     *
     * @param rowVector the column vector to add
     * @return the result of the addition
     */
    @Override
    public INDArray muliRowVector(INDArray rowVector) {
        return doRowWise(rowVector, 'm');
    }

    /**
     * In place addition of a column vector
     *
     * @param rowVector the column vector to add
     * @return the result of the addition
     */
    @Override
    public INDArray mulRowVector(INDArray rowVector) {
        return dup().muliRowVector(rowVector);
    }

    /**
     * In place addition of a column vector
     *
     * @param columnVector the column vector to add
     * @return the result of the addition
     */
    @Override
    public INDArray subiColumnVector(INDArray columnVector) {
        return doColumnWise(columnVector, 's');
    }

    /**
     * In place addition of a column vector
     *
     * @param columnVector the column vector to add
     * @return the result of the addition
     */
    @Override
    public INDArray subColumnVector(INDArray columnVector) {
        return dup().subiColumnVector(columnVector);
    }

    /**
     * In place addition of a column vector
     *
     * @param rowVector the column vector to add
     * @return the result of the addition
     */
    @Override
    public INDArray subiRowVector(INDArray rowVector) {
        return doRowWise(rowVector, 's');
    }

    /**
     * In place addition of a column vector
     *
     * @param rowVector the column vector to add
     * @return the result of the addition
     */
    @Override
    public INDArray subRowVector(INDArray rowVector) {
        return dup().subiRowVector(rowVector);
    }

    /**
     * In place addition of a column vector
     *
     * @param columnVector the column vector to add
     * @return the result of the addition
     */
    @Override
    public INDArray addiColumnVector(INDArray columnVector) {
        return doColumnWise(columnVector, 'a');
    }

    /**
     * In place addition of a column vector
     *
     * @param columnVector the column vector to add
     * @return the result of the addition
     */
    @Override
    public INDArray addColumnVector(INDArray columnVector) {
        return dup().addiColumnVector(columnVector);
    }

    /**
     * In place addition of a column vector
     *
     * @param rowVector the column vector to add
     * @return the result of the addition
     */
    @Override
    public INDArray addiRowVector(INDArray rowVector) {
        return doRowWise(rowVector, 'a');
    }

    /**
     * In place addition of a column vector
     *
     * @param rowVector the column vector to add
     * @return the result of the addition
     */
    @Override
    public INDArray addRowVector(INDArray rowVector) {
        return dup().addiRowVector(rowVector);
    }

    /**
     * Perform a copy matrix multiplication
     *
     * @param other the other matrix to perform matrix multiply with
     * @return the result of the matrix multiplication
     */
    @Override
    public INDArray mmul(INDArray other) {
        ensureNotCleanedUp();
        int[] shape = {rows(), other.columns()};
        INDArray result = create(shape, NDArrayFactory.FORTRAN);

        if(ordering() == NDArrayFactory.FORTRAN && other.ordering() == NDArrayFactory.FORTRAN) {
            return mmuli(other, result);
        }
        else {
            INDArray newThis = create(shape(),NDArrayFactory.FORTRAN);
            newThis.assign(this);
            INDArray newOther = create(other.shape(),NDArrayFactory.FORTRAN);
            newOther.assign(other);
            return newThis.mmuli(newOther,result);
        }
    }

    protected INDArray create(int[] shape, char ordering) {
        return Nd4j.create(shape, ordering);
    }

    /**
     * Perform an copy matrix multiplication
     *
     * @param other  the other matrix to perform matrix multiply with
     * @param result the result ndarray
     * @return the result of the matrix multiplication
     */
    @Override
    public INDArray mmul(INDArray other, INDArray result) {
        return dup().mmuli(other, result);
    }

    /**
     * in place (element wise) division of two matrices
     *
     * @param other the second ndarray to divide
     * @return the result of the divide
     */
    @Override
    public INDArray div(INDArray other) {
        return dup().divi(other);
    }

    /**
     * copy (element wise) division of two matrices
     *
     * @param other  the second ndarray to divide
     * @param result the result ndarray
     * @return the result of the divide
     */
    @Override
    public INDArray div(INDArray other, INDArray result) {
        return dup().divi(other, result);
    }

    /**
     * copy (element wise) multiplication of two matrices
     *
     * @param other the second ndarray to multiply
     * @return the result of the addition
     */
    @Override
    public INDArray mul(INDArray other) {
        return dup().muli(other);
    }

    /**
     * copy (element wise) multiplication of two matrices
     *
     * @param other  the second ndarray to multiply
     * @param result the result ndarray
     * @return the result of the multiplication
     */
    @Override
    public INDArray mul(INDArray other, INDArray result) {
        return dup().muli(other, result);
    }

    /**
     * copy subtraction of two matrices
     *
     * @param other the second ndarray to subtract
     * @return the result of the addition
     */
    @Override
    public INDArray sub(INDArray other) {
        return dup().subi(other);
    }

    /**
     * copy subtraction of two matrices
     *
     * @param other  the second ndarray to subtract
     * @param result the result ndarray
     * @return the result of the subtraction
     */
    @Override
    public INDArray sub(INDArray other, INDArray result) {
        return dup().subi(other, result);
    }

    /**
     * copy addition of two matrices
     *
     * @param other the second ndarray to add
     * @return the result of the addition
     */
    @Override
    public INDArray add(INDArray other) {
        return dup().addi(other);
    }

    /**
     * copy addition of two matrices
     *
     * @param other  the second ndarray to add
     * @param result the result ndarray
     * @return the result of the addition
     */
    @Override
    public INDArray add(INDArray other, INDArray result) {
        return dup().addi(other, result);
    }

    /**
     * Perform an copy matrix multiplication
     *
     * @param other the other matrix to perform matrix multiply with
     * @return the result of the matrix multiplication
     */
    @Override
    public INDArray mmuli(INDArray other) {
        return dup().mmuli(other, this);
    }

    /**
     * Perform an copy matrix multiplication
     *
     * @param other  the other matrix to perform matrix multiply with
     * @param result the result ndarray
     * @return the result of the matrix multiplication
     */
    @Override
    public INDArray mmuli(INDArray other, INDArray result) {
        ensureNotCleanedUp();
        INDArray otherArray = other;
        INDArray resultArray = result;

        if (other.shape().length > 2) {
            for (int i = 0; i < other.slices(); i++) {
                result.putSlice(i, slice(i).mmul(other.slice(i)));
            }

            return result;

        }
        LinAlgExceptions.assertMultiplies(this, other);


        if (other.isScalar()) {
            return muli(otherArray.getDouble(0), resultArray);
        }
        if (isScalar()) {
            return otherArray.muli(getDouble(0), resultArray);
        }

        /* check sizes and resize if necessary */


        if (result == this || result == other) {
            /* actually, blas cannot do multiplications in-place. Therefore, we will fake by
             * allocating a temporary object on the side and copy the result later.
             */
            INDArray temp = create(resultArray.shape(), getStrides(shape,NDArrayFactory.FORTRAN));

            if (otherArray.columns() == 1) {
                Nd4j.getBlasWrapper().level2().gemv(
                        BlasBufferUtil.getCharForTranspose(result)
                        ,BlasBufferUtil.getCharForTranspose(this)
                        ,1.0
                        ,this
                        ,otherArray
                        ,0.0
                        ,temp);
            }

            else {
                Nd4j.getBlasWrapper().level3().gemm(
                        BlasBufferUtil.getCharForTranspose(result)
                        ,BlasBufferUtil.getCharForTranspose(this)
                        ,BlasBufferUtil.getCharForTranspose(temp)
                        ,1.0
                        ,this
                        ,other
                        ,0.0
                        ,resultArray);
            }

            Nd4j.getBlasWrapper().copy(temp, resultArray);


        } else {
            if (otherArray.columns() == 1)
                Nd4j.getBlasWrapper().level2().gemv(
                        BlasBufferUtil.getCharForTranspose(resultArray)
                        ,BlasBufferUtil.getCharForTranspose(this)
                        ,1.0
                        ,this
                        ,otherArray
                        ,0.0,
                        resultArray);
            else {
                Nd4j.getBlasWrapper().level3().gemm(
                        BlasBufferUtil.getCharForTranspose(this)
                        ,BlasBufferUtil.getCharForTranspose(other)
                        ,BlasBufferUtil.getCharForTranspose(resultArray)
                        ,1.0
                        ,this
                        ,other
                        ,0.0,resultArray);

            }
        }

        if (Nd4j.ENFORCE_NUMERICAL_STABILITY)
            Nd4j.clearNans(resultArray);
        return resultArray;
    }

    private INDArray create(int[] shape, int[] stride) {
        return Nd4j.create(shape,stride);
    }

    /**
     * in place (element wise) division of two matrices
     *
     * @param other the second ndarray to divide
     * @return the result of the divide
     */
    @Override
    public INDArray divi(INDArray other) {
        return divi(other, this);
    }

    /**
     * in place (element wise) division of two matrices
     *
     * @param other  the second ndarray to divide
     * @param result the result ndarray
     * @return the result of the divide
     */
    @Override
    public INDArray divi(INDArray other, INDArray result) {
        ensureNotCleanedUp();
        if (other.isScalar()) {
            return divi(other.getDouble(0), result);
        }

        if (isScalar()) {
            return other.divi(getDouble(0), result);
        }

        Nd4j.getExecutioner().exec(new DivOp(this.linearView(), other.linearView(), result.linearView(), length()));

        if (Nd4j.ENFORCE_NUMERICAL_STABILITY)
            Nd4j.clearNans(result);
        return result;
    }

    /**
     * in place (element wise) multiplication of two matrices
     *
     * @param other the second ndarray to multiply
     * @return the result of the addition
     */
    @Override
    public INDArray muli(INDArray other) {
        return muli(other, this);
    }

    /**
     * in place (element wise) multiplication of two matrices
     *
     * @param other  the second ndarray to multiply
     * @param result the result ndarray
     * @return the result of the multiplication
     */
    @Override
    public INDArray muli(INDArray other, INDArray result) {
        ensureNotCleanedUp();
        if (other.isScalar()) {
            return muli(other.getDouble(0), result);
        }
        if (isScalar()) {
            return other.muli(getDouble(0), result);
        }

        Nd4j.getExecutioner().exec(new MulOp(linearView(), other.linearView(), result.linearView(), length()));

        if (Nd4j.ENFORCE_NUMERICAL_STABILITY)
            Nd4j.clearNans(result);

        return result;
    }

    /**
     * in place subtraction of two matrices
     *
     * @param other the second ndarray to subtract
     * @return the result of the addition
     */
    @Override
    public INDArray subi(INDArray other) {
        return subi(other, this);
    }

    /**
     * in place subtraction of two matrices
     *
     * @param other  the second ndarray to subtract
     * @param result the result ndarray
     * @return the result of the subtraction
     */
    @Override
    public INDArray subi(INDArray other, INDArray result) {
        ensureNotCleanedUp();
        if (other.isScalar()) {
            return subi(other.getDouble(0), result);
        }
        if (isScalar()) {
            return other.subi(getDouble(0), result);
        }

        Nd4j.getExecutioner().exec(new SubOp(linearView(), other.linearView(), result.linearView(), length()));

        if (Nd4j.ENFORCE_NUMERICAL_STABILITY)
            Nd4j.clearNans(result);

        return result;
    }

    /**
     * in place addition of two matrices
     *
     * @param other the second ndarray to add
     * @return the result of the addition
     */
    @Override
    public INDArray addi(INDArray other) {
        return addi(other, this);
    }

    /**
     * in place addition of two matrices
     *
     * @param other  the second ndarray to add
     * @param result the result ndarray
     * @return the result of the addition
     */
    @Override
    public INDArray addi(INDArray other, INDArray result) {
        ensureNotCleanedUp();
        if (other.isScalar()) {
            return result.addi(other.getDouble(0), result);
        }
        if (isScalar()) {
            return other.addi(getDouble(0), result);
        }


        Nd4j.getExecutioner().exec(new AddOp(linearView(), other, result));


        if (Nd4j.ENFORCE_NUMERICAL_STABILITY)
            Nd4j.clearNans(result);

        return result;
    }

    /**
     * Returns the normmax along the specified dimension
     *
     * @param dimension the dimension to getScalar the norm1 along
     * @return the norm1 along the specified dimension
     */
    @Override
    public INDArray normmax(int dimension) {
        return Nd4j.getExecutioner().exec(new NormMax(this),dimension);
    }

    /**
     * Reverse division
     *
     * @param other the matrix to divide from
     * @return
     */
    @Override
    public INDArray rdiv(INDArray other) {
        return dup().rdivi(other);
    }

    /**
     * Reverse divsion (in place)
     *
     * @param other
     * @return
     */
    @Override
    public INDArray rdivi(INDArray other) {
        return rdivi(other, this);
    }

    /**
     * Reverse division
     *
     * @param other  the matrix to subtract from
     * @param result the result ndarray
     * @return
     */
    @Override
    public INDArray rdiv(INDArray other, INDArray result) {
        return dup().rdivi(other, result);
    }

    /**
     * Reverse division (in-place)
     *
     * @param other  the other ndarray to subtract
     * @param result the result ndarray
     * @return the ndarray with the operation applied
     */
    @Override
    public INDArray rdivi(INDArray other, INDArray result) {
        return other.divi(this, result);
    }

    /**
     * Reverse subtraction
     *
     * @param other  the matrix to subtract from
     * @param result the result ndarray
     * @return
     */
    @Override
    public INDArray rsub(INDArray other, INDArray result) {
        return dup().rsubi(other, result);
    }

    /**
     * @param other
     * @return
     */
    @Override
    public INDArray rsub(INDArray other) {
        return dup().rsubi(other);
    }

    /**
     * @param other
     * @return
     */
    @Override
    public INDArray rsubi(INDArray other) {
        return rsubi(other, this);
    }

    /**
     * Reverse subtraction (in-place)
     *
     * @param other  the other ndarray to subtract
     * @param result the result ndarray
     * @return the ndarray with the operation applied
     */
    @Override
    public INDArray rsubi(INDArray other, INDArray result) {
        return other.subi(this, result);
    }

    /**
     * Set the value of the ndarray to the specified value
     *
     * @param value the value to assign
     * @return the ndarray with the values
     */
    @Override
    public INDArray assign(Number value) {
        data().assign(value);
        return this;
    }



    @Override
    public int linearIndex(int i) {
        if(isScalar() && i > 1)
            throw new IllegalArgumentException("Illegal index for scalar " + i);

        else if(isScalar() && i <= 1)
            return offset;

        int realStride = majorStride();
        int idx = offset + i * realStride;

        if (data != null && idx >= data.length())
            throw new IllegalArgumentException("Illegal index " + idx + " derived from " + i + " with offset of " + offset + " and stride of " + realStride);
        return idx;
    }

    /**
     * Returns the specified slice of this matrix.
     * In matlab, this would be equivalent to (given a 2 x 2 x 2):
     * A(:,:,x) where x is the slice you want to return.
     * <p/>
     * The slice is always relative to the final dimension of the matrix.
     *
     * @param slice the slice to return
     * @return the specified slice of this matrix
     */
    @Override
    public INDArray slice(int slice) {
        if (shape.length == 0) {
            if(slice == 0)
                return createScalarForIndex(slice,true);
            else
                throw new IllegalArgumentException("Can't slice a 0-d NDArray");

        }
        else if(slice == -1) {
            return slice(shape().length - 1);
        }
        //slice of a vector is a scalar
        else if (shape.length == 1) {
            if (size(0) == 1)
                return create(data, ArrayUtil.empty(), ArrayUtil.empty(), offset + slice);
            else
                return create(data, ArrayUtil.empty(), ArrayUtil.empty(), offset + slice * stride[0]);


        }


        //slice of a matrix is a vector
        if(isRowVector())
            return createScalarForIndex(slice,true);

        else {
            int offset = calcoffset(slice);
            if (size(0) == 1) {
                INDArray slice2 = create(data,
                        Arrays.copyOfRange(shape, 1, shape.length),
                        sliceStride(),
                        offset, ordering);
                return slice2;
            } else {
                if(ordering() == NDArrayFactory.FORTRAN) {
                    int[] sliceShape =  Arrays.copyOfRange(shape, 1, shape.length);
                    int[] retStride = sliceStride();
                    //enforce 1 x m
                    if(Shape.isRowVectorShape(sliceShape)) {
                        sliceShape = new int[] {1,sliceShape[0]};
                        retStride = ArrayUtil.of(1,retStride[0]);
                    }

                    INDArray slice2 = create(data,
                            sliceShape,
                            retStride,
                            offset, ordering);
                    return slice2;
                }
                else {
                    int[] sliceShape =  Arrays.copyOfRange(shape, 1, shape.length);
                    int[] retStride = sliceStride();
                    //enforce 1 x m
                    if(Shape.isRowVectorShape(sliceShape)) {
                        sliceShape = new int[] {1,sliceShape[0]};
                        retStride = ArrayUtil.of(retStride[0],1);

                    }

                    INDArray slice2 = create(data,
                            sliceShape,
                            retStride,
                            offset, ordering);
                    return slice2;
                }


            }


        }
    }


    protected INDArray createScalarForIndex(int i,boolean applyOffset) {
        return Nd4j.create(data(),new int[]{1,1},new int[]{1,1},applyOffset ? offset + i : i);
    }

    protected INDArray createScalar(double d) {
        return Nd4j.scalar(d);
    }


    public int[] sliceStride() {
        if(hasOneStride() && ordering() == NDArrayFactory.FORTRAN) {
            int[] ret = new int[stride.length - 1];
            Arrays.fill(ret,1);
            if(ordering() == NDArrayFactory.C) {
                int firstNon1 = getFirstNonOneStrideIdx();
                int count = 0;
                for(int i = firstNon1; i < stride.length; i++) {
                    if(stride[i] > elementStride()) {
                        ret[count++] = stride[i];
                    }
                }
            }

            else {
                int firstNon1 = getFirstNonOneStrideIdx();
                int count = 0;
                for(int i = firstNon1; i < stride.length; i++) {
                    if(stride[i] > elementStride()) {
                        ret[count++] = stride[i];
                    }
                }
            }


            return ret;
        }

        return  Arrays.copyOfRange(stride, 1, stride.length);
    }

    public boolean hasOneStride() {
        for(int i = 0; i < stride().length; i++)
            if(stride[i] == 1)
                return true;
        return false;
    }


    protected int calcoffset(int index) {
        if(stride[stride.length - 1] == 1 && NDArrayFactory.FORTRAN == ordering())
            return offset + index;
        return offset + index * majorStride();
    }


    /**
     * Returns the slice of this from the specified dimension
     *
     * @param slice     the dimension to return from
     * @param dimension the dimension of the slice to return
     * @return the slice of this matrix from the specified dimension
     * and dimension
     */
    @Override
    public INDArray slice(int slice, int dimension) {
        if (shape.length == 2) {
            //rows
            if (dimension == 1)
                return getRow(slice);


            else if (dimension == 0)
                return getColumn(slice);

            else throw new IllegalAccessError("Illegal dimension for matrix");

        }

        if (slice == shape.length - 1)
            return slice(dimension);

        INDArray slice2 = create(data,
                ArrayUtil.removeIndex(shape, dimension),
                ArrayUtil.removeIndex(stride, dimension),
                offset + slice * stride[dimension], ordering);
        return slice2;
    }

    /**
     * Fetch a particular number on a multi dimensional scale.
     *
     * @param indexes the indexes to get a number from
     * @return the number at the specified indices
     */
    @Override
    public INDArray getScalar(int... indexes) {
        int ix = offset;


        for (int i = 0; i < indexes.length; i++) {
            ix += indexes[i] * stride[i];
        }

        if (ix >= data.length())
            throw new IllegalArgumentException("Illegal index " + Arrays.toString(indexes));

        return createScalarForIndex(ix,false);
    }

    @Override
    public INDArray rdiv(Number n) {
        return dup().rdivi(n);
    }

    @Override
    public INDArray rdivi(Number n) {
        return rdivi(n, this);
    }

    @Override
    public INDArray rsub(Number n) {
        return dup().rsubi(n);
    }

    @Override
    public INDArray rsubi(Number n) {
        return rsubi(n, this);
    }

    @Override
    public INDArray div(Number n) {
        return dup().divi(n);
    }

    @Override
    public INDArray divi(Number n) {
        return divi(n, this);
    }

    @Override
    public INDArray mul(Number n) {
        return dup().muli(n);
    }

    @Override
    public INDArray muli(Number n) {
        return muli(n, this);
    }

    @Override
    public INDArray sub(Number n) {
        return dup().subi(n);
    }

    @Override
    public INDArray subi(Number n) {
        return subi(n, this);
    }

    @Override
    public INDArray add(Number n) {
        return dup().addi(n);
    }

    @Override
    public INDArray addi(Number n) {
        return addi(n, this);
    }



    /**
     * Replicate and tile array to fill out to the given shape
     *
     * @param shape the new shape of this ndarray
     * @return the shape to fill out to
     */
    @Override
    public INDArray repmat(int[] shape) {

        if(isVector())  {
            if(isRowVector()) {
                if(shape.length == 1) {
                    int[] shape2 = Ints.concat(new int[]{1},new int[]{ArrayUtil.prod(Ints.concat(shape,shape()))});
                    INDArray ret = Nd4j.create(shape2);
                    int curr = 0;
                    for(int i = 0; i < ret.length(); i++) {
                        ret.putScalar(i,getDouble(curr));
                        curr++;
                        if(curr >= length()) {
                            curr = 0;
                        }
                    }

                    return ret;

                }
                else {
                    INDArray ret = Nd4j.create(shape);
                    int curr = 0;
                    for(int i = 0; i < ret.length(); i++) {
                        ret.putScalar(i,getDouble(curr));
                        curr++;
                        if(curr >= length()) {
                            curr = 0;
                        }
                    }

                    return ret;
                }
            }
            else {
                INDArray ret = Nd4j.create(shape);
                int curr = 0;
                for(int i = 0; i < ret.length(); i++) {
                    ret.putScalar(i,getDouble(curr));
                    curr++;
                    if(curr >= length()) {
                        curr = 0;
                    }
                }

                return ret;
            }
        }


        int[] newShape = new int[shape.length];
        assert shape.length <= newShape.length : "Illegal shape: The passed in shape must be <= the current shape length";
        int[] oldShape = isRowVector() ? new int[]{1, this.shape[0]} : Arrays.copyOf(this.shape, 2);
        for (int i = 0; i < newShape.length; i++) {
            if (i < this.shape.length)
                newShape[i] = oldShape[i] * shape[i];
            else
                newShape[i] = oldShape[i];
        }

        INDArray result = create(newShape);
        //nd copy
        if (isScalar()) {
            for (int i = 0; i < result.length(); i++) {
                result.put(i, getScalar(0));

            }
        } else if (isRowVector()) {
            if (Shape.isColumnVectorShape(newShape))
                return transpose();
            else if (Shape.isMatrix(newShape)) {
                INDArray ret = create(newShape);
                for (int i = 0; i < ret.rows(); i++) {
                    ret.putRow(i, this);
                }
                return ret;
            }
        } else if (isMatrix()) {

            for (int c = 0; c < shape()[1]; c++) {
                for (int r = 0; r < shape()[0]; r++) {
                    for (int i = 0; i < rows(); i++) {
                        for (int j = 0; j < columns(); j++) {
                            result.put(r * rows() + i, c * columns() + j, getScalar(i, j));
                        }
                    }
                }
            }

        } else {
            int[] sliceRepmat = ArrayUtil.removeIndex(shape, 0);
            for (int i = 0; i < result.slices(); i++) {
                result.putSlice(i, repmat(sliceRepmat));
            }
        }


        return result;
    }

    /**
     * Insert a row in to this array
     * Will throw an exception if this
     * ndarray is not a matrix
     *
     * @param row   the row insert into
     * @param toPut the row to insert
     * @return this
     */
    @Override
    public INDArray putRow(int row, INDArray toPut) {
        assert toPut.isVector() && toPut.length() == columns : "Illegal length for row " + toPut.length() + " should have been " + columns;
        INDArray r = getRow(row);
        r.assign(toPut);
        return this;
    }

    /**
     * Insert a column in to this array
     * Will throw an exception if this
     * ndarray is not a matrix
     *
     * @param column the column to insert
     * @param toPut  the array to put
     * @return this
     */
    @Override
    public INDArray putColumn(int column, INDArray toPut) {
        assert toPut.isVector() && toPut.length() == rows() : "Illegal length for row " + toPut.length() + " should have been " + rows();
        INDArray r = getColumn(column);
        r.assign(toPut);
        return this;
    }


    @Override
    public double getDouble(int i) {
        if(i >= length()) {
            throw new IllegalArgumentException("Unable to get linear index >= " + length());

        }

        int idx = linearIndex(i);
        if (idx < 0)
            throw new IllegalStateException("Illegal index " + i);
        return data.getDouble(idx);
    }

    @Override
    public double getDouble(int i, int j) {
        return getDouble(new int[]{i, j});
    }

    @Override
    public float getFloat(int i) {
        return (float) getDouble(i);
    }

    @Override
    public float getFloat(int i, int j) {
        return (float) getDouble(i,j);
    }

    /**
     * Return transposed copy of this matrix.
     */
    @Override
    public INDArray transpose() {
        return transposei();
    }






    /**
     *
     * Return transposed version of this matrix.
     */
    @Override
    public INDArray transposei() {
        if (isRowVector()) {
            INDArray ret = create(shape.length == 1 ? new int[]{shape[0], 1} : ArrayUtil.reverseCopy(shape()));
            if(ret instanceof IComplexNDArray) {
                IComplexNDArray arr = (IComplexNDArray) ret;
                IComplexNDArray thisArr = (IComplexNDArray) this;
                for(int i = 0; i < ret.length(); i++) {
                    arr.putScalar(i,thisArr.getComplex(i));
                }

            }
            else {
                for(int i = 0; i < ret.length(); i++) {
                    ret.putScalar(i,getDouble(i));
                }
            }

            return ret;

        }
        else if (isColumnVector()) {
            INDArray ret = create(new int[]{1,shape[0]});
            if(ret instanceof IComplexNDArray) {
                IComplexNDArray arr = (IComplexNDArray) ret;
                IComplexNDArray thisArr = (IComplexNDArray) this;
                for(int i = 0; i < ret.length(); i++) {
                    arr.putScalar(i,thisArr.getComplex(i));
                }

            }
            else {
                for(int i = 0; i < ret.length(); i++) {
                    ret.putScalar(i,getDouble(i));
                }
            }

            return ret;
        }
        if(isMatrix()) {
            if(this instanceof IComplexNDArray) {
                IComplexNDArray arr = (IComplexNDArray) create(new int[]{columns(), rows()});
                IComplexNDArray arrThis = (IComplexNDArray) this;
                for(int i = 0; i < arr.rows(); i++) {
                    for(int j = 0; j < arr.columns(); j++)
                        arr.put(i,j,arrThis.getComplex(j, i));
                }

                return arr;
            }
            else {
                INDArray arr = create(columns(),rows());
                for(int i = 0; i < arr.rows(); i++) {
                    for(int j = 0; j < arr.columns(); j++)
                        arr.put(i,j,getDouble(j, i));
                }

                return arr;
            }

        }

        INDArray arr = create(ArrayUtil.reverseCopy(shape()));
        for(int i = 0; i < arr.slices(); i++) {
            arr.putSlice(i,arr.slice(i).transpose());
        }

        return arr;


    }


    /**
     * Reshape the ndarray in to the specified dimensions,
     * possible errors being thrown for invalid shapes
     *
     * @param shape
     * @return
     */
    @Override
    public INDArray reshape(int[] shape) {
        assert ArrayUtil.prod(shape) == ArrayUtil.prod(this.shape()) : "Illegal reshape must be of same length as data";
        return newShape(shape, ordering);
    }

    @Override
    public void checkDimensions(INDArray other) {
        assert Arrays.equals(shape, other.shape()) : " Other array should have been shape: " + Arrays.toString(shape) + " but was " + Arrays.toString(other.shape());
        assert Arrays.equals(stride, other.stride()) : " Other array should have been stride: " + Arrays.toString(stride) + " but was " + Arrays.toString(other.stride());
        assert offset == other.offset() : "Offset of this array is " + offset + " but other was " + other.offset();

    }


    /**
     * Returns the product along a given dimension
     *
     * @param dimension the dimension to getScalar the product along
     * @return the product along the specified dimension
     */
    @Override
    public INDArray prod(int dimension) {
        return Nd4j.getExecutioner().exec(new Prod(this),dimension);
    }

    /**
     * Returns the overall mean of this ndarray
     *
     * @param dimension the dimension to getScalar the mean along
     * @return the mean along the specified dimension of this ndarray
     */
    @Override
    public INDArray mean(int dimension) {
        return Nd4j.getExecutioner().exec(new Mean(this),dimension);
    }

    /**
     * Returns the overall variance of this ndarray
     *
     * @param dimension the dimension to getScalar the mean along
     * @return the mean along the specified dimension of this ndarray
     */
    @Override
    public INDArray var(int dimension) {
        return Nd4j.getExecutioner().exec(new Variance(this),dimension);
    }

    /**
     * Returns the overall max of this ndarray
     *
     * @param dimension the dimension to getScalar the mean along
     * @return the mean along the specified dimension of this ndarray
     */
    @Override
    public INDArray max(int dimension) {
        return Nd4j.getExecutioner().exec(new Max(this),dimension);
    }

    /**
     * Returns the overall min of this ndarray
     *
     * @param dimension the dimension to getScalar the mean along
     * @return the mean along the specified dimension of this ndarray
     */
    @Override
    public INDArray min(int dimension) {
        return Nd4j.getExecutioner().exec(new Min(this),dimension);
    }

    /**
     * Returns the sum along the last dimension of this ndarray
     *
     * @param dimension the dimension to getScalar the sum along
     * @return the sum along the specified dimension of this ndarray
     */
    @Override
    public INDArray sum(int dimension) {
        return Nd4j.getExecutioner().exec(new Sum(this),dimension);
    }


    /**
     * Returns the norm1 along the specified dimension
     *
     * @param dimension the dimension to getScalar the norm1 along
     * @return the norm1 along the specified dimension
     */
    @Override
    public INDArray norm1(int dimension) {
        return Nd4j.getExecutioner().exec(new Norm1(this),dimension);
    }


    /**
     * Standard deviation of an ndarray along a dimension
     *
     * @param dimension the dimension to getScalar the std along
     * @return the standard deviation along a particular dimension
     */
    @Override
    public INDArray std(int dimension) {
        return Nd4j.getExecutioner().exec(new StandardDeviation(this),dimension);
    }


    /**
     * Returns the norm2 along the specified dimension
     *
     * @param dimension the dimension to getScalar the norm2 along
     * @return the norm2 along the specified dimension
     */
    @Override
    public INDArray norm2(int dimension) {
        return Nd4j.getExecutioner().exec(new Norm2(this),dimension);
    }



    /**
     * Number of columns (shape[1]), throws an exception when
     * called when not 2d
     *
     * @return the number of columns in the array (only 2d)
     */
    @Override
    public int columns() {
        ensureNotCleanedUp();
        if (isMatrix()) {
            if (shape().length > 2)
                return Shape.squeeze(shape)[1];
            else if (shape().length == 2)
                return shape[1];
        }
        if (isVector()) {
            if (isColumnVector())
                return 1;
            else if(isRowVector() && shape.length > 1)
                return shape[1];
            else
                return shape[0];
        }
        throw new IllegalStateException("Unable to get number of of columns for a non 2d matrix");
    }

    /**
     * Returns the number of rows
     * in the array (only 2d) throws an exception when
     * called when not 2d
     *
     * @return the number of rows in the matrix
     */
    @Override
    public int rows() {
        ensureNotCleanedUp();
        if (isMatrix()) {
            if (shape().length > 2)
                return Shape.squeeze(shape)[0];
            else if (shape().length == 2)
                return shape[0];
        }

        else if (isVector()) {
            if (isRowVector())
                return 1;
            else
                return shape[0];
        }
        throw new IllegalStateException("Unable to get number of of rows for a non 2d matrix");
    }



    /**
     * Flattens the array for linear indexing
     *
     * @return the flattened version of this array
     */
    @Override
    public INDArray ravel() {

        ensureNotCleanedUp();
        INDArray ret = create(new int[]{1,length}, ordering);
        INDArray linear = linearView();
        for(int i = 0; i < length(); i++) {
            ret.putScalar(i,linear.getDouble(i));
        }

        return ret;

    }

    /**
     * Flattens the array for linear indexing
     *
     * @return the flattened version of this array
     */
    @Override
    public void sliceVectors(List<INDArray> list) {
        ensureNotCleanedUp();
        if (isVector())
            list.add(this);
        else {
            for (int i = 0; i < slices(); i++) {
                slice(i).sliceVectors(list);
            }
        }
    }

    /**
     * Reshape the matrix. Number of elements must not change.
     *
     * @param newRows
     * @param newColumns
     */
    @Override
    public INDArray reshape(int newRows, int newColumns) {
        ensureNotCleanedUp();
        assert newRows * newColumns == length : "Illegal new shape " + newRows + " x " + newColumns;
        return reshape(new int[]{newRows, newColumns});
    }

    /**
     * Get the specified column
     *
     * @param c
     */
    @Override
    public INDArray getColumn(int c) {
        ensureNotCleanedUp();
        if (shape.length == 2)
            return vectorAlongDimension(c,0);

        else if (isRowVector()) {
            return createScalarForIndex(c,true);
        } else if (isColumnVector() && c == 0)
            return this;


        else
            throw new IllegalArgumentException("Unable to getFloat scalar column of non 2d matrix");
    }


    /**
     * Get whole rows from the passed indices.
     *
     * @param rindices
     */
    @Override
    public INDArray getRows(int[] rindices) {
        ensureNotCleanedUp();
        INDArray rows = create(new int[]{rindices.length, columns()});
        for (int i = 0; i < rindices.length; i++) {
            rows.putRow(i, getRow(rindices[i]));
        }
        return rows;
    }

    /**
     * Returns a subset of this array based on the specified
     * indexes
     *
     * @param indexes the indexes in to the array
     * @return a view of the array with the specified indices
     */
    @Override
    public INDArray get(NDArrayIndex... indexes) {
        ensureNotCleanedUp();
        //fill in to match the rest of the dimensions: aka grab all the content
        //in the dimensions not filled in
        //also prune indices greater than the shape to be the shape instead

        indexes = Indices.adjustIndices(shape(), indexes);


        int[] offsets = Indices.offsets(indexes);
        int[] shape = Indices.shape(shape(), indexes);
        //no stride will help here, need to do manually
        if (!Indices.isContiguous(indexes)) {
            INDArray ret = create(shape);
            if (ret.isVector() && isVector()) {
                int[] indices = indexes[0].indices();
                for (int i = 0; i < ret.length(); i++) {
                    ret.putScalar(i, getDouble(indices[i]));
                }

                return ret;
            }
            if (!ret.isVector()) {
                if(slices() == 1) {
                    INDArray newSlice = slice(0);
                    NDArrayIndex[] putIndices = Arrays.copyOfRange(indexes, 1, indexes.length);
                    return newSlice.get(putIndices);
                }
                else if(indexes.length == 2 && isMatrix()) {
                    for (int i = 0; i < ret.slices(); i++) {
                        int sliceToGetFrom = indexes[0].indices()[i];
                        if(sliceToGetFrom >= slices())
                            break;

                        INDArray slice = slice(sliceToGetFrom);
                        INDArray retSlice = ret.slice(i);
                        int[] finalIndices = indexes[1].indices();
                        for(int j = 0; j < retSlice.length(); j++) {
                            if(j >= retSlice.length() || finalIndices[j] >= slice.length())
                                break;
                            retSlice.putScalar(j, slice.getDouble(finalIndices[j]));
                        }

                    }
                }
                else {
                    for (int i = 0; i < ret.slices(); i++) {
                        INDArray slice = slice(i);
                        INDArray putSlice = slice.get(Arrays.copyOfRange(indexes, 1, indexes.length));
                        ret.putSlice(i, putSlice);

                    }
                }

            } else {
                INDArray putSlice = slice(0).get(Arrays.copyOfRange(indexes, 1, indexes.length));
                ret.putSlice(0, putSlice);

            }


            return ret;
        }

        //This means upsampling.
        if (ArrayUtil.prod(shape) > length()) {
            INDArray ret = create(shape);
            NDArrayIndex slices = indexes[0];
            int[] indices = slices.indices();
            if(indexes.length == 1) {
                NDArrayIndex subRange = indexes[0];
                int count = 0;
                for(int i = 0; i < indices.length; i++) {
                    if(count >= ret.length())
                        count = 0;
                    int get = subRange.indices()[count];
                    ret.putScalar(count,getDouble(get));
                    count++;

                }
            }
            else if(isVector()) {
                NDArrayIndex[] subRange = Arrays.copyOfRange(indexes,1,indexes.length);

            }
            else {
                NDArrayIndex[] subRange = Arrays.copyOfRange(indexes,1,indexes.length);
                NDArrayIndex[] putRange = NDArrayIndex.rangeOfLength(subRange);
                for(int i = 0; i < indices.length; i++) {
                    INDArray sliceI = ret.slice(i);
                    INDArray thisSlice = slice(indices[i]);
                    sliceI.put(putRange,thisSlice.get(subRange));

                }
            }


            return ret;
        }

        int[] stride = this.stride();
        if(offsets[0] > 0 && ordering() == NDArrayFactory.C) {
            stride = ArrayUtil.reverseCopy(getStrides(shape,ordering));
        }
        else if(ordering() == NDArrayFactory.FORTRAN && Shape.isRowVectorShape(shape))
            stride = ArrayUtil.reverseCopy(stride);



        return subArray(offsets, shape,stride);
    }


    /**
     * Get whole columns
     * from the passed indices.
     *
     * @param cindices
     */
    @Override
    public INDArray getColumns(int[] cindices) {
        ensureNotCleanedUp();
        INDArray rows = create(rows(), cindices.length);
        for (int i = 0; i < cindices.length; i++) {
            rows.putColumn(i, getColumn(cindices[i]));
        }
        return rows;
    }

    protected INDArray create(int rows, int length) {
        return create(new int[]{rows,length});
    }

    /**
     * Get a copy of a row.
     *
     * @param r the row to get
     */
    @Override
    public INDArray getRow(int r) {
        ensureNotCleanedUp();
        if (shape.length == 2) {
            if (isColumnVector())
                return createScalarForIndex(r,true);
            return vectorAlongDimension(r,1);
        }

        else if(size(0) == 1 && shape.length == 3) {
            return slice(0).vectorAlongDimension(r,1);
        }

        else if (isRowVector() && r == 0)
            return this;


        else
            throw new IllegalArgumentException("Unable to getFloat row of non 2d matrix");
    }





    /**
     * Compare two matrices. Returns true if and only if other is also a
     * DoubleMatrix which has the same size and the maximal absolute
     * difference in matrix elements is smaller than 1e-6.
     *
     * @param o
     */
    @Override
    public boolean equals(Object o) {
        ensureNotCleanedUp();
        INDArray n = null;

        if (!(o instanceof INDArray))
            return false;

        if (n == null)
            n = (INDArray) o;

        //epsilon equals
        if (isScalar() && n.isScalar()) {
            if (data.dataType() == DataBuffer.Type.FLOAT) {
                double val = getDouble(0);
                double val2 = n.getDouble(0);
                return Math.abs(val - val2) < Nd4j.EPS_THRESHOLD;
            } else {
                double val = getDouble(0);
                double val2 = n.getDouble(0);
                return Math.abs(val - val2) < Nd4j.EPS_THRESHOLD;
            }

        } else if (isVector() && n.isVector()) {
            for (int i = 0; i < length; i++) {
                if (data.dataType() == DataBuffer.Type.FLOAT) {
                    double curr = getDouble(i);
                    double comp = n.getDouble(i);
                    if (Math.abs(curr - comp) > Nd4j.EPS_THRESHOLD)
                        return false;
                } else {
                    double curr = getDouble(i);
                    double comp = n.getDouble(i);
                    if (Math.abs(curr - comp) > Nd4j.EPS_THRESHOLD)
                        return false;
                }
            }

            return true;

        }


        if (!Shape.shapeEquals(shape(), n.shape()))
            return false;


        if (slices() != n.slices())
            return false;

        for (int i = 0; i < slices(); i++) {
            INDArray slice = slice(i);
            INDArray nSlice = n.slice(i);

            if (!slice.equals(nSlice))
                return false;
        }

        return true;

    }


    /**
     * Returns the shape(dimensions) of this array
     *
     * @return the shape of this matrix
     */
    public int[] shape() {
        ensureNotCleanedUp();
        return shape;
    }

    /**
     * Returns the stride(indices along the linear index for which each slice is accessed) of this array
     *
     * @return the stride of this array
     */
    @Override
    public int[] stride() {
        ensureNotCleanedUp();
        return stride;
    }


    @Override
    public int offset() {
        ensureNotCleanedUp();
        return offset;
    }

    @Override
    public char ordering() {
        ensureNotCleanedUp();
        return ordering;
    }

    /**
     * Returns the size of this array
     * along a particular dimension
     *
     * @param dimension the dimension to return from
     * @return the shape of the specified dimension
     */
    @Override
    public int size(int dimension) {
        ensureNotCleanedUp();
        if (isScalar()) {
            if (dimension == 0 || dimension == 1 || dimension < 0)
                return length;
            else
                throw new IllegalArgumentException("Illegal dimension for scalar " + dimension);
        }

        if(dimension < 0)
            return shape[shape.length + dimension];

        return shape[dimension];
    }

    @Override
    public int rank() {
        return shape().length;
    }

    /**
     * Returns the total number of elements in the ndarray
     *
     * @return the number of elements in the ndarray
     */
    @Override
    public int length() {
        return length;
    }

    /**
     * Broadcasts this ndarray to be the specified shape
     *
     * @param shape the new shape of this ndarray
     * @return the broadcasted ndarray
     */
    @Override
    public INDArray broadcast(int[] shape) {
        ensureNotCleanedUp();
        if (Shape.shapeEquals(shape, shape()))
            return this;
        boolean compatible = true;
        int count = shape.length - 1;
        int thisCount = this.shape.length - 1;
        for (int i = shape.length - 1; i > 0; i--) {
            if (count < 0 || thisCount < 0)
                break;
            if (shape[count] != shape()[thisCount] && shape[count] != 1 && shape()[thisCount] != 1) {
                compatible = false;
                break;
            }

            count--;
            thisCount--;
        }

        if (!compatible)
            throw new IllegalArgumentException("Incompatible broadcast from " + Arrays.toString(shape()) + " to " + Arrays.toString(shape));
        //broadcast to shape
        if (isScalar()) {
            INDArray ret = Nd4j.valueArrayOf(shape, getDouble(0));
            return ret;
        } else if (isColumnVector() && Shape.isMatrix(shape)) {
            INDArray ret = create(shape);
            for (int i = 0; i < ret.columns(); i++)
                ret.putColumn(i, this.dup());


            return ret;
        }

        else if(isRowVector()) {
            INDArray ret = create(shape);
            for(int i = 0; i < ret.rows(); i++)
                ret.putRow(i,this.dup());
            return ret;
        }



        int[] retShape = new int[shape.length];

        for (int i = 0; i < retShape.length; i++) {
            if(shape().length == 1) {
                if(i == 0) {
                    if (i < shape().length)
                        retShape[i] = Math.max(1, shape[i]);
                    else
                        retShape[i] = shape[i];
                }
                else {
                    if (i < shape().length)
                        retShape[i] = Math.max(shape[i], shape()[i]);
                    else
                        retShape[i] = shape[i];
                }
            }
            else {
                if (i < shape().length)
                    retShape[i] = Math.max(shape[i], shape()[i]);
                else
                    retShape[i] = shape[i];
            }

        }

        INDArray ret = create(retShape);
        INDArray linear = ret.linearView();
        INDArray thisLinear = linearView();
        int bufferIdx = 0;
        for (int i = 0; i < ret.length(); i++) {
            linear.putScalar(i, thisLinear.getDouble(bufferIdx));
            bufferIdx++;
            if (bufferIdx >= length())
                bufferIdx = 0;
        }

        return ret;


    }


    /**
     * Dimshuffle: an extension of permute that adds the ability
     * to broadcast various dimensions.
     * <p/>
     * See theano for more examples.
     * This will only accept integers and xs.
     * <p/>
     * An x indicates a dimension should be broadcasted rather than permuted.
     *
     * @param rearrange the dimensions to swap to
     * @return the newly permuted array
     */
    @Override
    public INDArray dimShuffle(Object[] rearrange, int[] newOrder, boolean[] broadCastable) {
        ensureNotCleanedUp();
        assert broadCastable.length == shape.length : "The broadcastable dimensions must be the same length as the current shape";

        boolean broadcast = false;
        Set<Object> set = new HashSet<>();
        for (int i = 0; i < rearrange.length; i++) {
            set.add(rearrange[i]);
            if (rearrange[i] instanceof Integer) {
                Integer j = (Integer) rearrange[i];
                if (j >= broadCastable.length)
                    throw new IllegalArgumentException("Illegal dimension, dimension must be < broadcastable.length (aka the real dimensions");
            } else if (rearrange[i] instanceof Character) {
                Character c = (Character) rearrange[i];
                if (c != 'x')
                    throw new IllegalArgumentException("Illegal input: Must be x");
                broadcast = true;

            } else
                throw new IllegalArgumentException("Only characters and integers allowed");
        }

        //just do permute
        if (!broadcast) {
            int[] ret = new int[rearrange.length];
            for (int i = 0; i < ret.length; i++)
                ret[i] = (Integer) rearrange[i];
            return permute(ret);
        } else {
            List<Integer> drop = new ArrayList<>();
            for (int i = 0; i < broadCastable.length; i++) {
                if (!set.contains(i)) {
                    if (broadCastable[i])
                        drop.add(i);
                    else
                        throw new IllegalArgumentException("We can't drop the given dimension because its not broadcastable");
                }

            }


            //list of dimensions to keep
            int[] shuffle = new int[broadCastable.length];
            int count = 0;
            for (int i = 0; i < rearrange.length; i++) {
                if (rearrange[i] instanceof Integer) {
                    shuffle[count++] = (Integer) rearrange[i];
                }
            }


            List<Integer> augment = new ArrayList<>();
            for (int i = 0; i < rearrange.length; i++) {
                if (rearrange[i] instanceof Character)
                    augment.add(i);
            }

            Integer[] augmentDims = augment.toArray(new Integer[1]);

            count = 0;

            int[] newShape = new int[shuffle.length + drop.size()];
            for (int i = 0; i < newShape.length; i++) {
                if (i < shuffle.length) {
                    newShape[count++] = shuffle[i];
                } else
                    newShape[count++] = drop.get(i);
            }


            INDArray ret = permute(newShape);
            List<Integer> newDims = new ArrayList<>();
            int[] shape = Arrays.copyOfRange(ret.shape(), 0, shuffle.length);
            for (int i = 0; i < shape.length; i++) {
                newDims.add(shape[i]);
            }

            for (int i = 0; i < augmentDims.length; i++) {
                newDims.add(augmentDims[i], 1);
            }

            int[] toReshape = ArrayUtil.toArray(newDims);


            ret = ret.reshape(toReshape);
            return ret;

        }


    }

    /**
     * See: http://www.mathworks.com/help/matlab/ref/permute.htsliceml
     *
     * @param rearrange the dimensions to swap to
     * @return the newly permuted array
     */
    @Override
    public INDArray permute(int[] rearrange) {
        ensureNotCleanedUp();
        if (rearrange.length != shape.length)
            return dup();

        checkArrangeArray(rearrange);
        int[] newShape = doPermuteSwap(shape, rearrange);

        if(isVector() || isMatrix()) {
            if(Arrays.equals(rearrange,ArrayUtil.reverseCopy(ArrayUtil.range(0,2))))
                return transpose();
            return this;
        }

        int[] newStride = doPermuteSwap(stride, rearrange);

        INDArray value = create(
                data(),
                newShape,
                newStride,
                offset,
                ordering);


        return value;


    }


    protected void copyRealTo(INDArray arr) {
        ensureNotCleanedUp();
        INDArray flattened = linearView();
        INDArray arrLinear = arr.linearView();
        for (int i = 0; i < flattened.length(); i++) {
            arrLinear.putScalar(i, flattened.getDouble(i));
        }

    }

    protected int[] doPermuteSwap(int[] shape, int[] rearrange) {
        ensureNotCleanedUp();
        int[] ret = new int[shape.length];
        for (int i = 0; i < shape.length; i++) {
            ret[i] = shape[rearrange[i]];
        }
        return ret;
    }


    protected void checkArrangeArray(int[] arr) {
        ensureNotCleanedUp();
        assert arr.length == shape.length : "Invalid rearrangement: number of arrangement != shape";
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] >= arr.length)
                throw new IllegalArgumentException("The specified dimensions can't be swapped. Given element " + i + " was >= number of dimensions");
            if (arr[i] < 0)
                throw new IllegalArgumentException("Invalid dimension: " + i + " : negative value");


        }

        for (int i = 0; i < arr.length; i++) {
            for (int j = 0; j < arr.length; j++) {
                if (i != j && arr[i] == arr[j])
                    throw new IllegalArgumentException("Permute array must have unique elements");
            }
        }

    }

    /**
     * Checks whether the matrix is a vector.
     */
    @Override
    public boolean isVector() {
        ensureNotCleanedUp();
        if(this.isVector == null)
            isVector =  isRowVector() || isColumnVector();
        return isVector;
    }

    @Override
    public boolean isSquare() {
        ensureNotCleanedUp();
        return isMatrix() && rows() == columns();
    }

    /**
     * Checks whether the matrix is a row vector.
     */
    @Override
    public boolean isRowVector() {
        ensureNotCleanedUp();
        if (shape().length == 1 || shape().length == 2 && shape[0] == 1)
            return true;

        return false;
    }

    /**
     * Checks whether the matrix is a column vector.
     */
    @Override
    public boolean isColumnVector() {
        ensureNotCleanedUp();
        if (shape().length == 1)
            return false;

        if (shape().length == 2 && shape()[1] == 1)
            return shape()[1] == 1;

        return false;

    }

    /**
     * Generate string representation of the matrix.
     */
    @Override
    public String toString() {
        ensureNotCleanedUp();
        if (isScalar()) {
            return element().toString();
        }


        if (isVector()) {
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            if (rows > 1)
                sb.append("[");
            int numElementsToPrint = Nd4j.MAX_ELEMENTS_PER_SLICE < 0 ? length : Nd4j.MAX_ELEMENTS_PER_SLICE;
            for (int i = 0; i < length; i++) {
                sb.append(getDouble(i));
                if (i < length - 1) {
                    if (rows > 1)
                        sb.append(",\n  ");
                    else
                        sb.append(", ");
                }
                if (i >= numElementsToPrint) {
                    int numElementsLeft = length - i;
                    //set towards the end of the buffer
                    if (numElementsLeft > numElementsToPrint) {
                        i += numElementsLeft - numElementsToPrint - 1;
                        sb.append(", ..., ");
                    }
                }

            }
            if (rows > 1)
                sb.append("]");
            sb.append("]\n ");
            return sb.toString();
        }


        StringBuilder sb = new StringBuilder();
        int length = shape[0];
        sb.append("[");
        if (length > 0) {
            sb.append(slice(0).toString());
            int slices = Nd4j.MAX_SLICES_TO_PRINT > 0 ? Nd4j.MAX_SLICES_TO_PRINT : slices();
            if (slices > slices())
                slices = slices();
            for (int i = 1; i < slices; i++) {
                sb.append(slice(i).toString());
                if (i < length - 1)
                    sb.append(" ,");

            }


        }
        sb.setLength(sb.length() - 2);
        sb.append("]\n");
        return sb.toString();
    }



    /**
     * Returns a scalar (individual element)
     * of a scalar ndarray
     *
     * @return the individual item in this ndarray
     */
    @Override
    public Object element() {
        ensureNotCleanedUp();
        if (!isScalar())
            throw new IllegalStateException("Unable to retrieve element from non scalar matrix");
        if (data.dataType() == DataBuffer.Type.FLOAT)
            return data.getFloat(offset);
        return data.getDouble(offset);
    }


    @Override
    public IComplexNDArray rdiv(IComplexNumber n) {
        return dup().rdivi(n);
    }

    @Override
    public IComplexNDArray rdivi(IComplexNumber n) {
        return rdivi(n, Nd4j.createComplex(shape()));

    }

    @Override
    public IComplexNDArray rsub(IComplexNumber n) {
        return dup().rsubi(n);
    }

    @Override
    public IComplexNDArray rsubi(IComplexNumber n) {
        return rsubi(n, Nd4j.createComplex(shape()));

    }

    @Override
    public IComplexNDArray div(IComplexNumber n) {
        return dup().divi(n);
    }

    @Override
    public IComplexNDArray divi(IComplexNumber n) {
        ensureNotCleanedUp();
        return divi(n, Nd4j.createComplex(shape()));

    }

    @Override
    public IComplexNDArray mul(IComplexNumber n) {
        return dup().muli(n);
    }

    @Override
    public IComplexNDArray muli(IComplexNumber n) {
        ensureNotCleanedUp();
        return muli(n, Nd4j.createComplex(shape()));

    }

    @Override
    public IComplexNDArray sub(IComplexNumber n) {
        return dup().subi(n);
    }

    @Override
    public IComplexNDArray subi(IComplexNumber n) {
        return subi(n, Nd4j.createComplex(shape()));
    }

    @Override
    public IComplexNDArray add(IComplexNumber n) {
        return dup().addi(n);
    }

    @Override
    public IComplexNDArray addi(IComplexNumber n) {
        ensureNotCleanedUp();
        return addi(n, Nd4j.createComplex(shape()));

    }

    @Override
    public IComplexNDArray rdiv(IComplexNumber n, IComplexNDArray result) {
        return dup().rdivi(n, result);
    }

    @Override
    public IComplexNDArray rdivi(IComplexNumber n, IComplexNDArray result) {
        return Nd4j.createComplex(this).rdivi(n, result);

    }

    @Override
    public IComplexNDArray rsub(IComplexNumber n, IComplexNDArray result) {
        return dup().rsubi(n, result);
    }

    @Override
    public IComplexNDArray rsubi(IComplexNumber n, IComplexNDArray result) {
        ensureNotCleanedUp();
        return Nd4j.createComplex(this).rsubi(n, result);
    }

    @Override
    public IComplexNDArray div(IComplexNumber n, IComplexNDArray result) {
        return dup().divi(n, result);
    }

    @Override
    public IComplexNDArray divi(IComplexNumber n, IComplexNDArray result) {
        ensureNotCleanedUp();
        return Nd4j.createComplex(this).divi(n, result);

    }

    @Override
    public IComplexNDArray mul(IComplexNumber n, IComplexNDArray result) {
        return dup().muli(n, result);
    }

    @Override
    public IComplexNDArray muli(IComplexNumber n, IComplexNDArray result) {
        ensureNotCleanedUp();
        return Nd4j.createComplex(this).muli(n, result);

    }

    @Override
    public IComplexNDArray sub(IComplexNumber n, IComplexNDArray result) {
        return dup().subi(n, result);
    }

    @Override
    public IComplexNDArray subi(IComplexNumber n, IComplexNDArray result) {
        ensureNotCleanedUp();
        return Nd4j.createComplex(this).subi(n, result);

    }

    @Override
    public IComplexNDArray add(IComplexNumber n, IComplexNDArray result) {
        return dup().addi(n, result);
    }

    @Override
    public IComplexNDArray addi(IComplexNumber n, IComplexNDArray result) {
        ensureNotCleanedUp();
        return Nd4j.createComplex(this).addi(n, result);

    }

    protected INDArray create(BaseNDArray baseNDArray) {
        return baseNDArray;
    }
}