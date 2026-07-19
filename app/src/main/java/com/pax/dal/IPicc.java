package com.pax.dal;

import com.pax.dal.entity.PiccCardInfo;
import com.pax.dal.entity.EDetectMode;

public interface IPicc {
    void open();
    PiccCardInfo detect(EDetectMode mode);
    void close();
}
