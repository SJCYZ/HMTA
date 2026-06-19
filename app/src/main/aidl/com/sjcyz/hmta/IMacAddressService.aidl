package com.sjcyz.hmta;

interface IMacAddressService {
    void destroy() = 16777114;

    void exit() = 1;

    String getP2pMacAddress() = 2;
    String getMacAddressByName(String name) = 3;
}
