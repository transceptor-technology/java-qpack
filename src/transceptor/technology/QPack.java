package transceptor.technology;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import static transceptor.technology.Types.*;

/**
 *
 * @author tristan
 */
public class QPack {

    private final ByteArrayOutputStream bytesContainer;
    private final DataOutputStream container;

    public QPack() {
        this.bytesContainer = new ByteArrayOutputStream();
        this.container = new DataOutputStream(bytesContainer);
    }

    /**
     * Reverses an array of bytes
     *
     * @param a containing a byte[] to reverse
     * @return reversed byte[]
     */
    private byte[] reverseArray(byte[] a) {
        for (int i = 0; i < a.length / 2; i++) {
            int temp = a[i];
            a[i] = a[a.length - i - 1];
            a[a.length - i - 1] = (byte) temp;
        }
        return a;
    }

    /**
     * Converts byte[] to an integer value
     *
     * @param b
     * @return
     */
    public Number convertByteToInt(byte[] b) {
        ByteBuffer wrapped = ByteBuffer.wrap(b);
        wrapped.order(ByteOrder.LITTLE_ENDIAN);
        switch (b.length) {
            case 1:
                return (int) wrapped.get(0);
            case 2:
                return (int) wrapped.getShort();
            case 4:
                return wrapped.getInt();
            case 8:
                return wrapped.getLong();
        }
        return 0;
    }

    /**
     * Converts byte[] to a double value
     *
     * @param b
     * @return
     */
    public double convertByteToDouble(byte[] b) {
        ByteBuffer wrapped = ByteBuffer.wrap(b);
        wrapped.order(ByteOrder.LITTLE_ENDIAN);
        return wrapped.getDouble();
    }

    /**
     * Packs the data into bytes
     *
     * @param data
     * @return array of bytes
     * @throws IOException
     */
    private byte[] _pack(Object data) throws IOException {
        // empty
        if (data == null) {
            container.writeByte(QP_BOOL_NULL);
            return bytesContainer.toByteArray();
        }

        // boolean
        if (data instanceof Boolean) {
            if ((boolean) data) {
                container.writeByte(QP_BOOL_TRUE);
            } else {
                container.writeByte(QP_BOOL_FALSE);
            }
            return bytesContainer.toByteArray();
        }

        // numbers
        if (data instanceof Number) {

            // double
            if (data instanceof Double) {
                if ((double) data == 0.0) {
                    container.write(QP_DOUBLE_0);
                } else if ((double) data == 1.0) {
                    container.write(QP_DOUBLE_1);
                } else if ((double) data == -1.0) {
                    container.write(QP_DOUBLE_N1);
                } else {
                    container.writeDouble((double) data);
                    container.write(QP_DOUBLE);
                }
                return reverseArray(bytesContainer.toByteArray());
            }

            // int
            long length = 0;

            if (data instanceof Integer) {
                length = (long) (int) data;
            } else if (data instanceof Long) {
                length = (long) data;
            }

            if (length < 64 && length >= 0) {
                container.write((int) length);
                return bytesContainer.toByteArray();
            }

            if (length < 0 && length >= -63) {
                container.write(63 - (int) length);
                return bytesContainer.toByteArray();
            }

            if (length <= Byte.MAX_VALUE && length >= Byte.MIN_VALUE) {
                container.writeByte((byte) length);
                container.writeByte(QP_INT8);
            } else if (length <= Short.MAX_VALUE && length >= Short.MIN_VALUE) {
                container.writeShort((short) length);
                container.writeByte(QP_INT16);
            } else if (length <= Integer.MAX_VALUE && length >= Integer.MIN_VALUE) {
                container.writeInt((int) length);
                container.writeByte(QP_INT32);
            } else if (length <= Long.MAX_VALUE && length >= Long.MIN_VALUE) {
                container.writeLong((long) length);
                container.writeByte(QP_INT64);
            } else {
                throw new IllegalArgumentException("qpack allows up to 64bit signed integers, got bit length: " + length);
            }
            return reverseArray(bytesContainer.toByteArray());
        }

        // string & byte[]
        if (data instanceof String || data instanceof byte[]) {
            byte[] bytes;
            if (data instanceof String) {
                bytes = ((String) data).getBytes();
            } else {
                bytes = (byte[]) data;
            }
            long length = bytes.length;

            if (length < 100) {
                container.writeByte(128 + (int) length);
            } else if (length < 256) {
                ByteBuffer bb = ByteBuffer.allocate(1);
                bb.order(ByteOrder.LITTLE_ENDIAN);
                bb.put((byte) length);
                container.writeByte(QP_RAW8);
                container.write(bb.array());
            } else if (length < 65536) {
                ByteBuffer bb = ByteBuffer.allocate(2);
                bb.order(ByteOrder.LITTLE_ENDIAN);
                bb.putShort((short) length);
                container.writeByte(QP_RAW16);
                container.write(bb.array());
            } else if (length < 4294967296L) {
                ByteBuffer bb = ByteBuffer.allocate(4);
                bb.order(ByteOrder.LITTLE_ENDIAN);
                bb.putInt((int) length);
                container.writeByte(QP_RAW32);
                container.write(bb.array());
            } else if (length < Long.MAX_VALUE) {
                ByteBuffer bb = ByteBuffer.allocate(8);
                bb.order(ByteOrder.LITTLE_ENDIAN);
                bb.putLong((long) length);
                container.writeByte(QP_RAW64);
                container.write(bb.array());
            } else {
                throw new IllegalArgumentException("raw string length too large to fit in qpack: " + length);
            }
            container.write(bytes);
            return bytesContainer.toByteArray();
        }

        // arrays
        if (data.getClass().isArray()) {
            int l = Array.getLength(data);
            if (l < 6) {
                container.writeByte(START_ARR + l);
                for (int i = 0; i < l; i++) {
                    _pack(Array.get(data, i));
                }
            } else {
                container.writeByte(QP_OPEN_ARRAY);
                for (int i = 0; i < l; i++) {
                    _pack(Array.get(data, i));
                }
                container.writeByte(QP_CLOSE_ARRAY);
            }
            return bytesContainer.toByteArray();
        }

        // collection
        if (data instanceof Collection<?>) {
            int l = ((Collection<?>) data).size();
            if (l < 6) {
                container.writeByte(START_ARR + l);
                for (Object o : ((Collection<?>) data)) {
                    _pack(o);
                }
            } else {
                container.writeByte(QP_OPEN_ARRAY);
                for (Object o : ((Collection<?>) data)) {
                    _pack(o);
                }
                container.writeByte(QP_CLOSE_ARRAY);
            }
            return bytesContainer.toByteArray();
        }

        // map
        if (data instanceof Map<?, ?>) {
            int l = ((Map<?, ?>) data).size();
            if (l < 6) {
                container.writeByte(START_MAP + l);
                for (Map.Entry<?, ?> o : ((Map<?, ?>) data).entrySet()) {
                    _pack(o.getKey());
                    _pack(o.getValue());
                }
            } else {
                container.writeByte(QP_OPEN_MAP);
                for (Map.Entry<?, ?> o : ((Map<?, ?>) data).entrySet()) {
                    _pack(o.getKey());
                    _pack(o.getValue());
                }
                container.writeByte(QP_CLOSE_MAP);
            }
            return bytesContainer.toByteArray();
        }

        throw new IllegalArgumentException("packing type " + data.getClass().getName() + " is not supported with qpack");
    }

    /**
     *
     * @param data
     * @return
     */
    private Object _unpack(byte[] data, int pos, int end, String decode) {
        int tp = (data[0] & 0xFF);
        System.out.println("tp = " + tp);
        pos++;
        // fixed integer
        if (tp < 64) {
            return tp;
        }
        // fixed negative integer
        if (tp < 124) {
            return 63 - tp;
        }
        // reserved for an object hook
        if (tp == QP_HOOK) {
            return 0;
        }
        // fixed doubles
        if (tp < 0x80) {
            return ((double) tp - 126);
        }
        // fixed string length
        if (tp < 0xe4) {
            int end_pos = pos + (tp - 128);
            return new String(Arrays.copyOfRange(data, pos, end_pos));
        }
        // string
        if (tp < 0xe8) {
            int qp_type = RAW_MAP.get(tp);
            int end_pos = pos + qp_type + data.length;
            pos += qp_type;
            return new String(Arrays.copyOfRange(data, pos, data.length));
        }
        // integer (double included)
        if (tp < 0xed) {
            int qp_type = NUMBER_MAP.get(tp);
            System.out.println("size  " + qp_type);
            System.out.println("array " + Arrays.toString(data));
            System.out.println("copy  " + Arrays.toString(Arrays.copyOfRange(data, pos, pos + qp_type)));
            if (tp == (QP_DOUBLE & 0xff)) {
                return convertByteToDouble(Arrays.copyOfRange(data, pos, pos + qp_type));
            } else {
                return convertByteToInt(Arrays.copyOfRange(data, pos, pos + qp_type));
            }
        }

        throw new IllegalArgumentException("Error in qpack at position " + pos);
    }

    /**
     * Packs data into array of bytes, this method makes use of _pack for
     * recursion
     *
     * @param data
     * @return
     */
    public byte[] pack(Object data) {
        byte[] output = null;
        try {
            output = _pack(data);
        } catch (IOException ex) {
            Logger.getLogger(QPack.class.getName()).log(Level.SEVERE, null, ex);
        }
        return output;
    }

    /**
     *
     * @param data
     * @return
     */
    public Object unpack(byte[] data) {
        return _unpack(data, 0, data.length, "utf8");
    }

}
